package dev.ragnarok.fenrir.mvp.presenter

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import dev.ragnarok.fenrir.*
import dev.ragnarok.fenrir.activity.ActivityUtils
import dev.ragnarok.fenrir.api.model.VKApiMessage
import dev.ragnarok.fenrir.crypt.AesKeyPair
import dev.ragnarok.fenrir.crypt.KeyExchangeService
import dev.ragnarok.fenrir.crypt.KeyLocationPolicy
import dev.ragnarok.fenrir.crypt.KeyPairDoesNotExistException
import dev.ragnarok.fenrir.db.Stores
import dev.ragnarok.fenrir.domain.*
import dev.ragnarok.fenrir.domain.IOwnersRepository.MODE_NET
import dev.ragnarok.fenrir.exception.UploadNotResolvedException
import dev.ragnarok.fenrir.link.LinkHelper
import dev.ragnarok.fenrir.longpoll.ILongpollManager
import dev.ragnarok.fenrir.longpoll.LongpollInstance
import dev.ragnarok.fenrir.media.record.AudioRecordException
import dev.ragnarok.fenrir.media.record.AudioRecordWrapper
import dev.ragnarok.fenrir.media.record.Recorder
import dev.ragnarok.fenrir.model.*
import dev.ragnarok.fenrir.mvp.presenter.base.RxSupportPresenter
import dev.ragnarok.fenrir.mvp.reflect.OnGuiCreated
import dev.ragnarok.fenrir.mvp.view.IChatView
import dev.ragnarok.fenrir.place.PlaceFactory
import dev.ragnarok.fenrir.push.OwnerInfo
import dev.ragnarok.fenrir.realtime.Processors
import dev.ragnarok.fenrir.service.ChatDownloadWorker
import dev.ragnarok.fenrir.settings.ISettings
import dev.ragnarok.fenrir.settings.Settings
import dev.ragnarok.fenrir.task.TextingNotifier
import dev.ragnarok.fenrir.upload.*
import dev.ragnarok.fenrir.util.*
import dev.ragnarok.fenrir.util.Objects
import dev.ragnarok.fenrir.util.RxUtils.*
import dev.ragnarok.fenrir.util.Utils.*
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.functions.Consumer
import io.reactivex.rxjava3.functions.Predicate
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList

class ChatPrensenter(accountId: Int, private val messagesOwnerId: Int,
                     initialPeer: Peer,
                     config: ChatConfig, savedInstanceState: Bundle?) : AbsMessageListPresenter<IChatView>(accountId, savedInstanceState) {

    private var peer: Peer
    private var subtitle: String? = null
    private val audioRecordWrapper: AudioRecordWrapper
    private var endOfContent: Boolean = false
    private var outConfig: ChatConfig
    private var draftMessageText: String? = null
    private var draftMessageId: Int? = null
    private var textingNotifier: TextingNotifier
    private var toolbarSubtitleHandler: ToolbarSubtitleHandler = ToolbarSubtitleHandler(this)
    private var draftMessageDbAttachmentsCount: Int = 0

    private var recordingLookup: Lookup

    private val messagesRepository: IMessagesRepository = Repository.messages
    private val stickersInteractor = InteractorFactory.createStickersInteractor()
    private val longpollManager: ILongpollManager = LongpollInstance.get()
    private val uploadManager: IUploadManager = Injection.provideUploadManager()

    private val words: ArrayList<StickersKeywords> = ArrayList()

    private var stickersWordsLoadingDisposable = Disposable.disposed()
    private var stickersWordsDisplayDisposable = Disposable.disposed()
    private var cacheLoadingDisposable = Disposable.disposed()
    private var netLoadingDisposable = Disposable.disposed()
    private var fetchConversationDisposable = Disposable.disposed()

    private var conversation: Conversation? = null

    fun getConversation(): Conversation? {
        return conversation
    }

    private var HronoType = false

    private var isLoadingFromDbNow = false
    private var isLoadingFromNetNow = false

    private val isLoadingNow: Boolean
        get() = isLoadingFromDbNow || isLoadingFromNetNow

    private val isRecordingNow: Boolean
        get() {
            val status = audioRecordWrapper.recorderStatus
            return status == Recorder.Status.PAUSED || status == Recorder.Status.RECORDING_NOW
        }

    private val isGroupChat: Boolean
        get() = Peer.isGroupChat(peerId)

    private val peerId: Int
        get() = peer.id

    val inInverHrono: Boolean
        get() = HronoType

    private val isEncryptionSupport: Boolean
        get() = Peer.isUser(peerId) && peerId != messagesOwnerId && !Settings.get().other().isDisabled_encryption

    private val isEncryptionEnabled: Boolean
        get() = Settings.get()
                .security()
                .isMessageEncryptionEnabled(messagesOwnerId, peerId)

    private var currentPhotoCameraUri: Uri? = null

    init {
        audioRecordWrapper = AudioRecordWrapper.Builder(App.instance)
                .setFileExt(RECORD_EXT_MP3)
                .build()

        if (savedInstanceState == null) {
            peer = initialPeer
            outConfig = config

            if (config.initialText.nonEmpty()) {
                draftMessageText = config.initialText
            }
        } else {
            peer = savedInstanceState.getParcelable(SAVE_PEER)!!
            outConfig = savedInstanceState.getParcelable(SAVE_CONFIG)!!
            currentPhotoCameraUri = savedInstanceState.getParcelable(SAVE_CAMERA_FILE_URI)
            restoreFromInstanceState(savedInstanceState)
        }

        fetchConversationThenCachedThenActual(false)

        if (savedInstanceState == null) {
            tryToRestoreDraftMessage(!draftMessageText.isNullOrEmpty())
        }

        resolveAccountHotSwapSupport()
        textingNotifier = TextingNotifier(messagesOwnerId)

        val predicate = Predicate<IAttachmentsRepository.IBaseEvent> { event ->
            draftMessageId != null
                    && event.accountId == messagesOwnerId
                    && event.attachToId == draftMessageId
        }

        val attachmentsRepository = Injection.provideAttachmentsRepository()

        appendDisposable(attachmentsRepository
                .observeAdding()
                .filter(predicate)
                .toMainThread()
                .subscribe { event -> onRepositoryAttachmentsAdded(event.attachments.size) })

        appendDisposable(attachmentsRepository
                .observeRemoving()
                .filter(predicate)
                .toMainThread()
                .subscribe { onRepositoryAttachmentsRemoved() })

        appendDisposable(messagesRepository
                .observeMessageUpdates()
                .toMainThread()
                .subscribe { onMessagesUpdate(it) })

        recordingLookup = Lookup(1000)
                .also {
                    it.setCallback {
                        resolveRecordingTimeView()
                    }
                }

        appendDisposable(longpollManager.observeKeepAlive()
                .toMainThread()
                .subscribe({ onLongpollKeepAliveRequest() }, ignore()))

        appendDisposable(Processors.realtimeMessages()
                .observeResults()
                .filter { result -> result.accountId == messagesOwnerId }
                .toMainThread()
                .subscribe { result ->
                    for (msg in result.data) {
                        val m = msg.message

                        if (m != null && peerId == m.peerId) {
                            onRealtimeMessageReceived(m)
                        }
                    }
                })

        appendDisposable(uploadManager.observeAdding()
                .toMainThread()
                .subscribe({ onUploadAdded(it) }, ignore()))

        appendDisposable(uploadManager.observeDeleting(true)
                .toMainThread()
                .subscribe({ onUploadRemoved(it) }, ignore()))

        appendDisposable(uploadManager.observeResults()
                .toMainThread()
                .subscribe({ onUploadResult(it) }, ignore()))

        appendDisposable(uploadManager.obseveStatus()
                .toMainThread()
                .subscribe({ onUploadStatusChange(it) }, ignore()))

        appendDisposable(uploadManager.observeProgress()
                .toMainThread()
                .subscribe({ onUploadProgressUpdate(it) }, ignore()))

        appendDisposable(messagesRepository.observePeerUpdates()
                .toMainThread()
                .subscribe({ onPeerUpdate(it) }, ignore()))

        appendDisposable(Repository.owners.observeUpdates()
                .toMainThread()
                .subscribe { onUserUpdates(it) })

        appendDisposable(messagesRepository.observeTextWrite()
                .flatMap { list -> Flowable.fromIterable(list) }
                .toMainThread()
                .subscribe { onUserWriteInDialog(it) })

        updateSubtitle()
    }

    fun invert_Hrono() {
        HronoType = true
        resolveOptionMenu()
    }

    fun reset_Hrono() {
        HronoType = false
        resolveOptionMenu()
    }

    private fun onUserWriteInDialog(writeText: WriteText) {
        if (peerId == writeText.peerId)
            displayUserTextingInToolbar(writeText.userId)
    }

    private fun onUserUpdates(updates: List<UserUpdate>) {
        for (update in updates) {
            if (update.accountId == accountId && isChatWithUser(update.userId)) {
                update.online?.run {
                    subtitle = if (isOnline) {
                        getString(R.string.online)
                    } else {
                        getString(R.string.offline) + ", " + getString(R.string.last_seen_sex_unknown, AppTextUtils.getDateFromUnixTime(lastSeen))
                    }

                    resolveToolbarSubtitle()
                }
            }
        }
    }

    fun fireResendSwipe(position: Int) {
        if (position < 0 || data.size <= position) {
            return
        }
        fireForwardToHereClick(ArrayList(Collections.singleton(data[position])))
    }

    fun fireTranscript(voiceMessageId: String, messageId: Int) {
        appendDisposable(messagesRepository.recogniseAudioMessage(accountId, messageId, voiceMessageId)
                .compose(applySingleIOToMainSchedulers())
                .subscribe({ }, { t: Throwable? -> showError(view, t) }))
    }

    fun removeDialog() {
        appendDisposable(messagesRepository.deleteDialog(accountId, peerId)
                .compose(applyCompletableIOToMainSchedulers())
                .subscribe({ onDialogRemovedSuccessfully(accountId) }, { t: Throwable? -> showError(view, t) }))
    }

    private fun onDialogRemovedSuccessfully(oldaccountId: Int) {
        view?.showSnackbar(R.string.deleted, true)
        if (accountId != oldaccountId) {
            return
        }
        data.clear()
        view?.notifyDataChanged()
    }

    private fun onPeerUpdate(updates: List<PeerUpdate>) {
        var requireListUpdate = false

        for (update in updates) {
            if (update.accountId != messagesOwnerId || update.peerId != peerId) continue

            update.readIn?.run {
                conversation?.inRead = messageId
                lastReadId.incoming = messageId
            }

            update.unread?.run {
                conversation?.unreadCount = count
                requireListUpdate = true
            }

            update.readOut?.run {
                conversation?.outRead = messageId
                lastReadId.outgoing = messageId
                requireListUpdate = true
            }

            update.pin?.run {
                conversation?.pinned = pinned
                resolvePinnedMessageView()
            }

            update.title?.run {
                conversation?.title = title
                peer.title = title
                resolveToolbarTitle()
            }
        }

        if (requireListUpdate) {
            view?.notifyDataChanged()
        }
    }

    private fun fetchConversationThenCachedThenActual(refresh: Boolean) {
        fetchConversationDisposable = messagesRepository.getConversationSingle(messagesOwnerId, peer.id, if (refresh) Mode.NET else Mode.ANY)
                .fromIOToMain()
                .subscribe({ onConversationFetched(refresh, it) }, { onConversationFetchFail(it) })
    }

    private fun onConversationFetchFail(throwable: Throwable) {
        showError(view, throwable)
    }

    private fun onConversationFetched(refresh: Boolean, data: Conversation) {
        conversation = data
        view?.convert_to_keyboard(data.currentKeyboard)

        resolvePinnedMessageView()
        resolveInputView()

        lastReadId.incoming = data.inRead
        lastReadId.outgoing = data.outRead

        if (!refresh) {
            loadAllCachedData()
        }
        requestAtStart()

        if (!refresh) {
            if (Settings.get().other().isHint_stickers) {
                stickersWordsLoadingDisposable = stickersInteractor.getKeywordsStickers(accountId)
                        .fromIOToMain()
                        .subscribe({
                            words.addAll(it)
                        }, ignore())
            }
        }
    }

    private fun onUploadProgressUpdate(data: List<IUploadManager.IProgressUpdate>) {
        edited?.run {
            for (update in data) {
                val index = attachments.indexOfFirst {
                    it.attachment is Upload && (it.attachment as Upload).id == update.id
                }

                if (index != -1) {
                    val upload = attachments[index].attachment as Upload
                    if (upload.status == Upload.STATUS_UPLOADING) {
                        upload.progress = update.progress
                        view?.notifyEditUploadProgressUpdate(index, update.progress)
                    }
                }
            }
        }
    }

    private fun onUploadStatusChange(upload: Upload) {
        edited?.run {
            val index = attachments.indexOfFirst {
                it.attachment is Upload && (it.attachment as Upload).id == upload.id
            }

            if (index != -1) {
                (attachments[index].attachment as Upload).apply {
                    status = upload.status
                    errorText = upload.errorText
                }

                view?.notifyEditAttachmentChanged(index)
            }
        }
    }

    fun fireNewChatPhotoSelected(photo: LocalPhoto) {
        val intent = UploadIntent(accountId, UploadDestination.forChatPhoto(Peer.toChatId(peerId)))
                .setAutoCommit(true)
                .setFileId(photo.imageId)
                .setFileUri(photo.fullImageUri)
                .setSize(Upload.IMAGE_SIZE_FULL)
        uploadManager.enqueue(listOf(intent))
    }

    private fun onUploadResult(pair: Pair<Upload, UploadResult<*>>) {
        val destination = pair.first.destination
        if (destination.method == Method.PHOTO_TO_CHAT && Peer.toChatId(peerId) == destination.ownerId) {
            val res = pair.second.result as String?
            peer.avaUrl = res
            view?.displayToolbarAvatar(peer)
        } else {
            edited?.run {
                if (message.id == destination.id && destination.method == Method.TO_MESSAGE) {
                    val photo: AbsModel = pair.second.result as AbsModel
                    val sizeBefore = attachments.size

                    attachments.add(AttachmenEntry(true, photo))
                    view?.notifyEditAttachmentsAdded(sizeBefore, 1)
                    resolveAttachmentsCounter()
                    resolvePrimaryButton()
                }
            }
        }
    }

    private fun onUploadRemoved(ids: IntArray) {
        edited?.run {
            for (id in ids) {
                val index = attachments.indexOfFirst {
                    it.attachment is Upload && (it.attachment as Upload).id == id
                }

                if (index != -1) {
                    attachments.removeAt(index)
                    view?.notifyEditAttachmentRemoved(index)
                }
            }
        }
    }

    private fun onUploadAdded(uploads: List<Upload>) {
        edited?.run {
            val filtered = uploads
                    .asSequence()
                    .filter { u ->
                        u.destination.id == message.id && u.destination.method == Method.TO_MESSAGE
                    }.map {
                        AttachmenEntry(true, it)
                    }.toList()

            if (filtered.isNotEmpty()) {
                attachments.addAll(0, filtered)
                view?.notifyEditAttachmentsAdded(0, filtered.size)
            }
        }
    }

    @OnGuiCreated
    private fun resolvePinnedMessageView() {
        conversation?.run {
            view?.displayPinnedMessage(pinned, hasFlag(acl, Conversation.AclFlags.CAN_CHANGE_PIN))
        } ?: run {
            view?.displayPinnedMessage(null, false)
        }
    }

    private fun resolveInputView() {
        conversation?.run {
            if (isGroupChannel) view?.hideInputView()
        }
    }

    @OnGuiCreated
    private fun resolveEditedMessageViews() {
        view?.displayEditingMessage(edited?.message)
    }

    private fun onLongpollKeepAliveRequest() {
        checkLongpoll()
    }

    private fun onRepositoryAttachmentsRemoved() {
        draftMessageDbAttachmentsCount--
        resolveAttachmentsCounter()
        resolvePrimaryButton()
    }

    private fun onRepositoryAttachmentsAdded(count: Int) {
        draftMessageDbAttachmentsCount += count
        resolveAttachmentsCounter()
        resolvePrimaryButton()
    }

    private fun loadAllCachedData() {
        setCacheLoadingNow(true)
        cacheLoadingDisposable = messagesRepository.getCachedPeerMessages(messagesOwnerId, peer.id)
                .fromIOToMain()
                .subscribe({ onCachedDataReceived(it) }, { onCachedDataReceived(Collections.emptyList()) })
    }

    private fun onCachedDataReceived(data: List<Message>) {
        setCacheLoadingNow(false)
        onAllDataLoaded(data, appendToList = false, isCache = true)
    }

    private fun onNetDataReceived(messages: List<Message>, startMessageId: Int?) {
        cacheLoadingDisposable.dispose()

        isLoadingFromDbNow = false
        endOfContent = messages.isEmpty()
        if (HronoType && startMessageId == null)
            endOfContent = true

        setNetLoadingNow(false)
        onAllDataLoaded(messages, startMessageId != null, isCache = false)
    }

    fun fireDeleteChatPhoto() {
        appendDisposable(messagesRepository.deleteChatPhoto(accountId, Peer.toChatId(peerId))
                .compose(applyCompletableIOToMainSchedulers())
                .subscribe({ peer.avaUrl = null; view?.displayToolbarAvatar(peer) }, { t: Throwable? -> showError(view, t) }))
    }

    @SuppressLint("CheckResult")
    private fun onAllDataLoaded(messages: List<Message>, appendToList: Boolean, isCache: Boolean) {
        val all = !appendToList

        //сохранение выделенных сообщений
        if (all) {
            val selectedList = data.filter {
                it.isSelected
            }

            for (selected in selectedList) {
                for (item in messages) {
                    if (item.id == selected.id) {
                        item.isSelected = true
                        break
                    }
                }
            }
        }

        if (all && data.isNotEmpty()) {
            data.clear()
            data.addAll(messages)
            view?.notifyDataChanged()
        } else {
            val startSize = data.size
            data.addAll(messages)
            view?.notifyMessagesUpAdded(startSize, messages.size)
        }

        resolveEmptyTextVisibility()
        if (Settings.get().other().isAuto_read && !isCache) {
            appendDisposable(CheckMessages().compose(applySingleIOToMainSchedulers()).subscribe({ t -> if (t) startSendService() else readAllUnreadMessagesIfExists() }) { })
        }
    }

    @SuppressLint("CheckResult")
    private fun CheckMessages(): Single<Boolean> {
        if (isHiddenAccount(accountId)) return Single.just(false)
        var need = false
        for (i: Message in data) {
            if (i.status == MessageStatus.ERROR) {
                need = true
                messagesRepository.enqueueAgain(messagesOwnerId, i.id).blockingAwait()
            }
        }
        return Single.just(need)
    }

    private fun setCacheLoadingNow(cacheLoadingNow: Boolean) {
        this.isLoadingFromDbNow = cacheLoadingNow
        resolveLoadUpHeaderView()
    }

    fun fireLoadUpButtonClick() {
        if (canLoadMore()) {
            requestMore()
        }
    }

    private fun canLoadMore(): Boolean {
        return data.isNotEmpty() && !isLoadingFromDbNow && !isLoadingFromNetNow && !HronoType && !endOfContent
    }

    @OnGuiCreated
    private fun resolveLoadUpHeaderView() {
        val loading = isLoadingNow
        view?.setupLoadUpHeaderState(if (loading) LoadMoreState.LOADING else if (endOfContent) LoadMoreState.END_OF_LIST else LoadMoreState.CAN_LOAD_MORE)
    }

    private fun requestAtStart() {
        requestFromNet(null)
    }

    private fun setNetLoadingNow(netLoadingNow: Boolean) {
        this.isLoadingFromNetNow = netLoadingNow
        resolveLoadUpHeaderView()
    }

    private fun requestFromNet(startMessageId: Int?) {
        setNetLoadingNow(true)

        val peerId = this.peerId
        netLoadingDisposable = messagesRepository.getPeerMessages(messagesOwnerId, peerId, COUNT, null, startMessageId, !HronoType, HronoType)
                .fromIOToMain()
                .subscribe({ messages -> onNetDataReceived(messages, startMessageId) }, { this.onMessagesGetError(it) })
    }

    private fun onMessagesGetError(t: Throwable) {
        setNetLoadingNow(false)
        PersistentLogger.logThrowable("Chat issues", getCauseIfRuntime(t))
        showError(view, getCauseIfRuntime(t))
    }

    private fun requestMore() {
        val lastId = if (data.size > 0) data[data.size - 1].id else null
        requestFromNet(lastId)
    }

    private fun onMessagesRestoredSuccessfully(id: Int) {
        data.find {
            it.id == id
        }?.run {
            isDeleted = false
            isDeletedForAll = false
            view?.notifyDataChanged()
        }
    }

    fun fireTextEdited(s: String?) {
        if (!Settings.get().other().isHint_stickers) {
            return
        }
        stickersWordsDisplayDisposable.dispose()
        if (isEmpty(s) || isEmpty(words)) {
            if (isGuiReady) {
                view?.updateStickers(Collections.emptyList())
            }
            return
        }
        stickersWordsDisplayDisposable = findStickerByWord(s!!.trim())
                .delay(500, TimeUnit.MILLISECONDS)
                .fromIOToMain()
                .subscribe({ stickers -> if (isGuiReady) view?.updateStickers(stickers) }, ignore())
    }

    private fun findStickerByWord(s: String): Single<List<Sticker>> {
        for (i in words) {
            for (v in i.keywords) {
                if (s.equals(v, true)) {
                    return Single.just(i.stickers)
                }
            }
        }
        return Single.just(Collections.emptyList())
    }

    fun fireDraftMessageTextEdited(s: String) {
        edited?.run {
            val wasEmpty = body.isNullOrBlank()
            body = s
            if (wasEmpty != body.isNullOrBlank()) {
                resolvePrimaryButton()
            }
            return
        }

        val oldState = canSendNormalMessage()
        draftMessageText = s
        val newState = canSendNormalMessage()

        if (oldState != newState) {
            resolvePrimaryButton()
        }

        if (!isHiddenAccount(accountId) && !Settings.get().main().isDont_write) {
            readAllUnreadMessagesIfExists()
            textingNotifier.notifyAboutTyping(peerId)
        }
    }

    fun fireSendClick() {
        if (canSendNormalMessage()) {
            sendImpl()
        }
    }

    private fun sendImpl() {
        val securitySettings = Settings.get().security()

        val trimmedBody = AppTextUtils.safeTrim(draftMessageText, null)
        val encryptionEnabled = securitySettings.isMessageEncryptionEnabled(messagesOwnerId, peerId)

        @KeyLocationPolicy
        var keyLocationPolicy = KeyLocationPolicy.PERSIST
        if (encryptionEnabled) {
            keyLocationPolicy = securitySettings.getEncryptionLocationPolicy(messagesOwnerId, peerId)
        }

        val builder = SaveMessageBuilder(messagesOwnerId, peer.id)
                .also {
                    it.body = trimmedBody
                    it.draftMessageId = draftMessageId
                    it.isRequireEncryption = encryptionEnabled
                    it.keyLocationPolicy = keyLocationPolicy
                }

        val fwds = ArrayList<Message>()

        for (model in outConfig.models) {
            if (model is FwdMessages) {
                fwds.addAll(model.fwds)
            } else {
                builder.attach(model)
            }
        }

        builder.forwardMessages = fwds

        outConfig.models.clear()
        outConfig.initialText = null

        draftMessageId = null
        draftMessageText = null
        draftMessageDbAttachmentsCount = 0

        view?.resetInputAttachments()

        resolveAttachmentsCounter()
        resolveDraftMessageText()
        resolvePrimaryButton()

        sendMessage(builder)

        if (outConfig.isCloseOnSend) {
            view?.doCloseAfterSend()
        }
    }

    @SuppressLint("CheckResult")
    private fun sendMessage(builder: SaveMessageBuilder) {
        messagesRepository.put(builder)
                .fromIOToMain()
                .doOnSuccess {
                    if (Settings.get().main().isOver_ten_attach && it.isHasAttachments && it.attachments.size() > 10) {
                        val temp = it.attachments.toList()
                        val att: ArrayList<AbsModel> = ArrayList()
                        for (i in 10 until temp.size - 1) {
                            att.add(temp[i])
                        }
                        outConfig.appendAll(att)
                        resolveAttachmentsCounter()
                        resolvePrimaryButton()
                    }
                    startSendService()
                }.subscribe(WeakConsumer(messageSavedConsumer), WeakConsumer(messageSaveFailConsumer))
    }

    private val messageSavedConsumer: Consumer<Message> = Consumer { onMessageSaveSuccess(it) }
    private val messageSaveFailConsumer: Consumer<Throwable> = Consumer { onMessageSaveError(it) }

    private fun onMessageSaveError(throwable: Throwable) {
        view?.run {
            when (throwable) {
                is KeyPairDoesNotExistException -> safeShowError(view, R.string.no_encryption_keys)
                is UploadNotResolvedException -> safeShowError(view, R.string.upload_not_resolved_exception_message)
                else -> RxSupportPresenter.safeShowError(view, throwable.message)
            }
        }
    }

    private fun onMessageSaveSuccess(message: Message) {
        addMessageToList(message)
        view?.notifyDataChanged()
        view?.ScrollTo(0)
    }

    private fun startSendService() {
        messagesRepository.runSendingQueue()
    }

    fun fireAttachButtonClick() {
        edited?.run {
            view?.showEditAttachmentsDialog(attachments)
            return
        }

        if (draftMessageId == null) {
            draftMessageId = Stores.getInstance()
                    .messages()
                    .saveDraftMessageBody(messagesOwnerId, peerId, draftMessageText)
                    .blockingGet()
        }

        val destination = UploadDestination.forMessage(draftMessageId!!)
        view?.goToMessageAttachmentsEditor(accountId, messagesOwnerId, destination, draftMessageText, outConfig.models) // TODO: 15.08.2017
    }

    private fun canSendNormalMessage(): Boolean {
        return calculateAttachmentsCount() > 0 || trimmedNonEmpty(draftMessageText) || nowUploadingToEditingMessage()
    }

    @OnGuiCreated
    private fun resolveEmptyTextVisibility() {
        view?.setEmptyTextVisible(safeIsEmpty(data) && !isLoadingNow)
    }

    private fun nowUploadingToEditingMessage(): Boolean {
        val messageId = draftMessageId ?: return false

        val current = uploadManager.current
        return current.nonEmpty() && current.get().destination.compareTo(messageId, UploadDestination.WITHOUT_OWNER, Method.TO_MESSAGE)
    }

    @OnGuiCreated
    private fun resolveAttachmentsCounter() {
        edited?.run {
            view?.displayDraftMessageAttachmentsCount(calculateAttachmentsCount(this))
        } ?: run {
            view?.displayDraftMessageAttachmentsCount(calculateAttachmentsCount())
        }
    }

    @OnGuiCreated
    private fun resolveDraftMessageText() {
        edited?.run {
            view?.displayDraftMessageText(body)
        } ?: run {
            view?.displayDraftMessageText(draftMessageText)
        }
    }

    @OnGuiCreated
    private fun resolveToolbarTitle() {
        view?.displayToolbarTitle(peer.title)
    }

    @OnGuiCreated
    private fun resolveToolbarAvatar() {
        view?.displayToolbarAvatar(peer)
    }

    fun fireRecordCancelClick() {
        audioRecordWrapper.stopRecording()
        onRecordingStateChanged()
        resolveRecordPauseButton()
    }

    private fun onRecordingStateChanged() {
        resolvePrimaryButton()
        syncRecordingLookupState()
    }

    fun fireRecordingButtonClick() {
        if (!hasAudioRecordPermissions()) {
            view?.requestRecordPermissions()
            return
        }

        startRecordImpl()
    }

    private fun sendRecordingMessageImpl(file: File) {
        view?.ScrollTo(0)
        val builder = SaveMessageBuilder(messagesOwnerId, peerId).setVoiceMessageFile(file)
        this.sendMessage(builder)
    }

    fun fireRecordSendClick() {
        try {
            val file = audioRecordWrapper.stopRecordingAndReceiveFile()
            sendRecordingMessageImpl(file)
        } catch (e: AudioRecordException) {
            e.printStackTrace()
        }

        onRecordingStateChanged()
        resolveRecordPauseButton()
    }

    fun fireRecordResumePauseClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val isRecorderPaused = audioRecordWrapper.recorderStatus == Recorder.Status.PAUSED
                if (!isRecorderPaused) {
                    audioRecordWrapper.pause()
                } else {
                    audioRecordWrapper.doRecord()
                }

                resolveRecordPauseButton()
            } catch (e: AudioRecordException) {
                e.printStackTrace()
            }

        } else {
            view?.showToast(R.string.pause_is_not_supported, true)
        }
    }

    @OnGuiCreated
    private fun resolvePrimaryButton() {
        if (isRecordingNow) {
            view?.setupPrimaryButtonAsRecording()
        } else {
            edited?.run {
                view?.setupPrimaryButtonAsEditing(canSave)
            } ?: run {
                view?.setupPrimaryButtonAsRegular(canSendNormalMessage(), !isHiddenAccount(accountId))
            }
        }
    }

    @OnGuiCreated
    private fun resolveRecordPauseButton() {
        val paused = audioRecordWrapper.recorderStatus == Recorder.Status.PAUSED
        val available = audioRecordWrapper.isPauseSupported
        view?.setupRecordPauseButton(available, !paused)
    }

    fun fireRecordPermissionsResolved() {
        if (hasAudioRecordPermissions()) {
            startRecordImpl()
        }
    }

    private fun startRecordImpl() {
        try {
            audioRecordWrapper.doRecord()
        } catch (e: AudioRecordException) {
            e.printStackTrace()
        }

        onRecordingStateChanged()
        resolveRecordingTimeView()
    }

    private fun hasAudioRecordPermissions(): Boolean {
        val app = applicationContext

        val recordPermission = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO)
        val writePermission = ContextCompat.checkSelfPermission(app, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        return recordPermission == PackageManager.PERMISSION_GRANTED && writePermission == PackageManager.PERMISSION_GRANTED
    }

    private fun syncRecordingLookupState() {
        if (isRecordingNow) {
            recordingLookup.start()
        } else {
            recordingLookup.stop()
        }
    }

    @OnGuiCreated
    private fun resolveRecordingTimeView() {
        if (isRecordingNow) {
            view?.displayRecordingDuration(audioRecordWrapper.currentRecordDuration)
        }
    }

    private fun addMessageToList(message: Message) {
        addElementToList(message, data, MESSAGES_COMPARATOR)
    }

    private fun onMessagesUpdate(updates: List<MessageUpdate>) {
        for (update in updates) {
            val targetIndex = indexOf(update.messageId)

            update.statusUpdate?.run {
                if (vkid != null) {
                    // message was sent
                    val alreadyExist = indexOf(vkid) != -1

                    if (alreadyExist) {
                        if (targetIndex != -1) {
                            data.removeAt(targetIndex)
                        }
                    } else {
                        if (targetIndex != -1) {
                            val message = data[targetIndex]
                            message.status = status
                            message.id = vkid

                            data.removeAt(targetIndex)
                            addMessageToList(message)
                        }
                    }
                } else {
                    //message not sent
                    if (targetIndex != -1) {
                        val message = data[targetIndex]
                        message.status = status
                    }
                }
            } ?: run {
                if (targetIndex != -1) {
                    update.deleteUpdate?.run {
                        data[targetIndex].isDeleted = isDeleted
                        data[targetIndex].isDeletedForAll = isDeletedForAll
                    }

                    update.importantUpdate?.run {
                        data[targetIndex].isImportant = isImportant
                    }
                }
            }
        }

        view?.notifyDataChanged()
        if (Settings.get().other().isAuto_read)
            readAllUnreadMessagesIfExists()
    }

    private fun onRealtimeMessageReceived(message: Message) {
        if (message.peerId != peer.id || messagesOwnerId != message.accountId) {
            return
        }

        if (message.isChatTitleUpdate) {
            peer.title = message.actionText
            resolveToolbarTitle()
        }

        val index = data.indexOfFirst {
            it.id == message.id
        }

        if (index != -1) {
            data.removeAt(index)
        }

        if (message.isOut && message.randomId > 0) {
            val unsentIndex = data.indexOfFirst {
                it.randomId == message.randomId && !it.isSent
            }

            if (unsentIndex != -1) {
                data.removeAt(unsentIndex)
            }
        }

        addMessageToList(message)
        view?.notifyDataChanged()
    }

    private fun isChatWithUser(userId: Int): Boolean {
        return !isGroupChat && Peer.toUserId(peerId) == userId
    }

    private fun displayUserTextingInToolbar(ownerId: Int) {
        if (!Settings.get().ui().isDisplay_writing)
            return

        view?.displayWriting(ownerId)
        toolbarSubtitleHandler.restoreToolbarWithDelay()
    }

    fun ResolveWritingInfo(context: Context, owner_id: Int) {
        appendDisposable(OwnerInfo.getRx(context, Settings.get().accounts().current, owner_id)
                .compose(applySingleIOToMainSchedulers())
                .subscribe({ t -> view?.displayWriting(t.owner) }, { run {} }))
    }

    private fun updateSubtitle() {
        subtitle = null

        when (Peer.getType(peerId)) {
            Peer.GROUP -> {
                subtitle = null
                resolveToolbarSubtitle()
            }

            Peer.CHAT -> appendDisposable(messagesRepository.getChatUsers(accountId, Peer.toChatId(peerId))
                    .compose(applySingleIOToMainSchedulers())
                    .subscribe({ t ->
                        run {
                            subtitle = getString(R.string.chat_users_count, t.size)
                            resolveToolbarSubtitle()
                        }
                    }, { run { resolveToolbarSubtitle() } }))


            Peer.USER -> appendDisposable(Stores.getInstance()
                    .owners()
                    .getLocalizedUserActivity(messagesOwnerId, Peer.toUserId(peerId))
                    .compose(applyMaybeIOToMainSchedulers())
                    .subscribe({ s ->
                        subtitle = s
                        resolveToolbarSubtitle()
                    }, { Analytics.logUnexpectedError(it) }, { this.resolveToolbarSubtitle() }))
        }
    }

    private fun canEdit(message: Message): Boolean {
        return message.isOut && Unixtime.now() - message.date < 24 * 60 * 60
                && !message.isSticker && !message.isVoiceMessage && !message.isGraffity && !message.isCall
    }

    private fun canChangePin(): Boolean {
        return conversation?.run {
            hasFlag(acl, Conversation.AclFlags.CAN_CHANGE_PIN)
        } ?: run {
            false
        }
    }

    private fun doStar(): Boolean {
        val selectionCount = countOfSelection(data)
        if (selectionCount <= 0)
            return false
        return !data.find {
            it.isSelected
        }!!.isImportant
    }

    private fun canStar(): Boolean {
        val selectionCount = countOfSelection(data)
        if (selectionCount <= 0)
            return false

        val iterator = data.iterator()
        var status = false
        var has = false
        while (iterator.hasNext()) {
            val message = iterator.next()
            if (!message.isSelected) {
                continue
            }
            if (!has) {
                has = true
                status = message.isImportant
            } else {
                if (message.isImportant != status || message.status != MessageStatus.SENT)
                    return false
            }
        }
        return true
    }

    @OnGuiCreated
    override fun resolveActionMode() {
        val selectionCount = countOfSelection(data)
        if (selectionCount > 0) {
            if (selectionCount == 1) {
                val message = data.find {
                    it.isSelected
                }!!

                view?.showActionMode(selectionCount.toString(), canEdit(message), canChangePin(), canStar(), doStar())
            } else {
                view?.showActionMode(selectionCount.toString(), false, false, canStar(), doStar())
            }
        } else {
            view?.finishActionMode()
        }
    }

    @OnGuiCreated
    private fun resolveToolbarSubtitle() {
        view?.displayToolbarSubtitle(subtitle)
    }

    @OnGuiCreated
    private fun hideWriting() {
        view?.hideWriting()
    }

    private fun checkLongpoll() {
        if (isGuiResumed && accountId != ISettings.IAccountsSettings.INVALID_ID) {
            longpollManager.keepAlive(accountId)
        }
    }

    public override fun onGuiResumed() {
        super.onGuiResumed()
        checkLongpoll()
        Processors.realtimeMessages().registerNotificationsInterceptor(id, Pair.create(messagesOwnerId, peerId))
    }

    public override fun onGuiPaused() {
        super.onGuiPaused()
        checkLongpoll()
        Processors.realtimeMessages().unregisterNotificationsInterceptor(id)
    }

    private fun tryToRestoreDraftMessage(ignoreBody: Boolean) {
        appendDisposable(Stores.getInstance()
                .messages()
                .findDraftMessage(messagesOwnerId, peerId)
                .compose(applyMaybeIOToMainSchedulers())
                .subscribe({ draft -> onDraftMessageRestored(draft, ignoreBody) }, { Analytics.logUnexpectedError(it) }))
    }

    private fun calculateAttachmentsCount(message: EditedMessage): Int {
        var count = 0

        for (entry in message.attachments) {
            if (entry.attachment is FwdMessages) {
                if (!isEmpty((entry.attachment as FwdMessages).fwds)) {
                    count += (entry.attachment as FwdMessages).fwds.size
                }
            } else if (entry.attachment !is Upload) {
                count++
            }
        }

        return count
    }

    private fun calculateAttachmentsCount(): Int {
        var outConfigCount = 0
        for (model in outConfig.models) {
            if (model is FwdMessages) {
                outConfigCount += model.fwds.size
            } else {
                outConfigCount++
            }
        }

        return outConfigCount + draftMessageDbAttachmentsCount
    }

    private fun onDraftMessageRestored(message: DraftMessage, ignoreBody: Boolean) {
        if (isEmpty(draftMessageText)) {
            draftMessageDbAttachmentsCount = message.attachmentsCount
            draftMessageId = message.id

            if (!ignoreBody) {
                draftMessageText = message.body
            }
        }

        resolveAttachmentsCounter()
        resolvePrimaryButton()
        resolveDraftMessageText()
    }

    fun resetDraftMessage() {
        draftMessageText = null
        resolvePrimaryButton()
        resolveDraftMessageText()
    }

    private fun resolveAccountHotSwapSupport() {
        setSupportAccountHotSwap(!Peer.isGroupChat(peerId))
    }

    override fun onDestroyed() {
        stickersWordsDisplayDisposable.dispose()
        stickersWordsLoadingDisposable.dispose()
        cacheLoadingDisposable.dispose()
        netLoadingDisposable.dispose()
        fetchConversationDisposable.dispose()

        saveDraftMessageBody()

        toolbarSubtitleHandler.release()

        recordingLookup.stop()
        recordingLookup.setCallback(null)

        textingNotifier.shutdown()
        super.onDestroyed()
    }

    fun saveDraftMessageBody() {
        Stores.getInstance()
                .messages()
                .saveDraftMessageBody(messagesOwnerId, peerId, draftMessageText)
                .subscribeIOAndIgnoreResults()
    }

    override fun onMessageClick(message: Message) {
        if (message.status == MessageStatus.ERROR) {
            view?.showErrorSendDialog(message)
        } else {
            readAllUnreadMessagesIfExists()
        }
    }

    private fun readAllUnreadMessagesIfExists() {
        if (isHiddenAccount(accountId)) return
        val last = if (data.nonEmpty()) data[0] else return

        if (!last.isOut && last.id > lastReadId.incoming) {
            lastReadId.incoming = last.id

            view?.notifyDataChanged()

            appendDisposable(messagesRepository.markAsRead(messagesOwnerId, peer.id, last.id)
                    .fromIOToMain()
                    .subscribe(dummy(), { t -> showError(view, t) }))
        }
    }

    fun fireMessageRestoreClick(message: Message) {
        restoreMessage(message.id)
    }

    private fun restoreMessage(messageId: Int) {
        appendDisposable(messagesRepository.restoreMessage(messagesOwnerId, peerId, messageId)
                .fromIOToMain()
                .subscribe({ onMessagesRestoredSuccessfully(messageId) }, { t -> showError(view, t) }))
    }

    fun fireEditMessageResult(accompanyingModels: ModelsBundle) {
        outConfig.models = accompanyingModels

        resolveAttachmentsCounter()
        resolvePrimaryButton()
    }

    override fun onActionModeDeleteClick() {
        super.onActionModeDeleteClick()
        deleteSelectedMessages()
    }

    fun fireActionModeStarClick() {
        val sent = ArrayList<Int>(0)
        val iterator = data.iterator()
        var hasChanged = false
        var isImportant = false
        while (iterator.hasNext()) {
            val message = iterator.next()
            if (!message.isSelected) {
                continue
            }
            if (!hasChanged) {
                hasChanged = true
                isImportant = message.isImportant
            }
            sent.add(message.id)
        }
        if (sent.nonEmpty()) {
            appendDisposable(messagesRepository.markAsImportant(messagesOwnerId, peer.id, sent, if (!isImportant) 1 else 0)
                    .fromIOToMain()
                    .subscribe(dummy(), { t -> showError(view, t) }))
        }
    }

    /**
     * Удаление отмеченных сообщений
     * можно удалять сообщения в статусе
     * STATUS_SENT - отправляем запрос на сервис, удаление из списка произойдет через longpoll
     * STATUS_QUEUE || STATUS_ERROR - просто удаляем из БД и списка
     * STATUS_WAITING_FOR_UPLOAD - отменяем "аплоад", удаляем из БД и списка
     */
    private fun deleteSelectedMessages() {
        val sent = ArrayList<Int>(0)
        val canDeleteForAll = ArrayList<Int>(0)

        var hasChanged = false
        val iterator = data.iterator()

        while (iterator.hasNext()) {
            val message = iterator.next()

            if (!message.isSelected) {
                continue
            }

            when (message.status) {
                MessageStatus.SENT -> {
                    if (canDeleteForAll(message)) {
                        canDeleteForAll.add(message.id)
                    } else {
                        sent.add(message.id)
                    }
                }
                MessageStatus.QUEUE, MessageStatus.ERROR, MessageStatus.SENDING -> {
                    deleteMessageFromDbAsync(message)
                    iterator.remove()
                    hasChanged = true
                }
                MessageStatus.WAITING_FOR_UPLOAD -> {
                    cancelWaitingForUploadMessage(message.id)
                    deleteMessageFromDbAsync(message)
                    iterator.remove()
                    hasChanged = true
                }
                MessageStatus.EDITING -> {
                    TODO()
                }
            }
        }

        if (sent.nonEmpty()) {
            deleteSentImpl(sent, false)
        }

        if (hasChanged) {
            view?.notifyDataChanged()
        }

        if (canDeleteForAll.isNotEmpty()) {
            view?.showDeleteForAllDialog(canDeleteForAll)
        }
    }

    private fun deleteSentImpl(ids: Collection<Int>, forAll: Boolean) {
        appendDisposable(messagesRepository.deleteMessages(messagesOwnerId, peerId, ids, forAll)
                .fromIOToMain()
                .subscribe(dummy(), { t -> showError(view, t) }))
    }

    private fun canDeleteForAll(message: Message): Boolean {
        return message.isOut && Unixtime.now() - message.date < 24 * 60 * 60 && peerId != accountId
    }

    private fun cancelWaitingForUploadMessage(messageId: Int) {
        val destination = UploadDestination.forMessage(messageId)
        uploadManager.cancelAll(messagesOwnerId, destination)
    }

    fun fireSendAgainClick(message: Message) {
        appendDisposable(messagesRepository.enqueueAgain(messagesOwnerId, message.id)
                .fromIOToMain()
                .subscribe({ this.startSendService() }, { Analytics.logUnexpectedError(it) }))
    }

    private fun deleteMessageFromDbAsync(message: Message) {
        subscribeOnIOAndIgnore(Stores.getInstance()
                .messages()
                .deleteMessage(messagesOwnerId, message.id))
    }

    fun fireErrorMessageDeleteClick(message: Message) {
        val index = indexOf(message.id)
        if (index != -1) {
            data.removeAt(index)
            view?.notifyItemRemoved(index)
        }

        deleteMessageFromDbAsync(message)
    }

    fun fireRefreshClick() {
        fetchConversationThenCachedThenActual(true)
    }

    fun fireShortLinkClick(context: Context) {
        PlaceFactory.getShortLinks(accountId).tryOpenWith(context)
    }

    fun fireShow_Profile() {
        view?.showUserWall(accountId, peerId)
    }

    fun fireLeaveChatClick() {
        val chatId = Peer.toChatId(peerId)
        val accountId = super.getAccountId()

        appendDisposable(messagesRepository.removeChatMember(accountId, chatId, accountId)
                .fromIOToMain()
                .subscribe(dummy(), { t -> showError(view, t) }))
    }

    fun fireChatTitleClick() {
        view?.showChatTitleChangeDialog(peer.title)
    }

    fun fireChatMembersClick() {
        view?.goToChatMembers(accountId, Peer.toChatId(peerId))
    }

    fun fireChatDownloadClick(context: Context, action: String) {
        if (!CheckUpdate.isFullVersion(context)) {
            return
        }
        val downloadWork = OneTimeWorkRequest.Builder(ChatDownloadWorker::class.java)
        val data = Data.Builder()
        data.putInt(Extra.OWNER_ID, peerId)
        data.putInt(Extra.ACCOUNT_ID, accountId)
        data.putString(Extra.TITLE, conversation?.title)
        data.putString(Extra.ACTION, action)
        downloadWork.setInputData(data.build())
        WorkManager.getInstance(context).enqueue(downloadWork.build())
    }

    fun fireDialogAttachmentsClick() {
        view?.goToConversationAttachments(accountId, peerId) // TODO: 15.08.2017
    }

    fun fireSearchClick() {
        view?.goToSearchMessage(accountId, peer) // TODO: 15.08.2017
    }

    fun fireImageUploadSizeSelected(streams: List<Uri>, size: Int) {
        uploadStreamsImpl(streams, size, false)
    }

    private fun uploadStreams(streams: List<Uri>, mime: String) {
        if (streams.nullOrEmpty() || !mime.nonEmpty()) return

        val size = Settings.get()
                .main()
                .uploadImageSize

        val isVideo = ActivityUtils.isMimeVideo(mime)

        if (size == null && !isVideo) {
            view?.showImageSizeSelectDialog(streams)
        } else {
            uploadStreamsImpl(streams, size, isVideo)
        }
    }

    @OnGuiCreated
    private fun resolveResumePeer() {
        view?.notifyChatResume(accountId, peerId, peer.title, peer.avaUrl)
        if (Objects.nonNull(conversation)) {
            view?.convert_to_keyboard(conversation!!.currentKeyboard)
        }
    }

    private fun uploadStreamsImpl(streams: List<Uri>, size: Int?, is_video: Boolean) {
        outConfig.uploadFiles = null
        outConfig.uploadFilesMimeType = null

        view?.resetUploadImages()

        if (draftMessageId == null) {
            draftMessageId = Stores.getInstance()
                    .messages()
                    .saveDraftMessageBody(messagesOwnerId, peerId, draftMessageText)
                    .blockingGet()
        }

        val destination = if (is_video) UploadDestination.forMessage(draftMessageId!!, MessageMethod.VIDEO) else UploadDestination.forMessage(draftMessageId!!)
        val intents = ArrayList<UploadIntent>(streams.size)

        if (!is_video) {
            for (uri in streams) {
                intents.add(UploadIntent(messagesOwnerId, destination)
                        .setAutoCommit(true)
                        .setFileUri(uri)
                        .setSize(size!!))
            }
        } else {
            for (uri in streams) {
                intents.add(UploadIntent(messagesOwnerId, destination)
                        .setAutoCommit(true)
                        .setFileUri(uri))
            }
        }

        uploadManager.enqueue(intents)
    }

    fun fireUploadCancelClick() {
        outConfig.uploadFiles = null
        outConfig.uploadFilesMimeType = null
    }

    @OnGuiCreated
    private fun resolveInputImagesUploading() {
        if (outConfig.uploadFiles.nonEmpty() && outConfig.uploadFilesMimeType.nonEmpty()) {
            uploadStreams(outConfig.uploadFiles, outConfig.uploadFilesMimeType)
        }
    }

    fun fireMessagesLookup(message: Message) {
        view?.goToMessagesLookup(accountId, message.peerId, message.id)
    }

    fun fireChatTitleTyped(newValue: String) {
        val chatId = Peer.toChatId(peerId)

        appendDisposable(messagesRepository.editChat(messagesOwnerId, chatId, newValue)
                .fromIOToMain()
                .subscribe(dummy(), { t -> showError(view, t) }))
    }

    fun fireForwardToHereClick(messages: ArrayList<Message>) {
        for (i in outConfig.models) {
            if (i is FwdMessages) {
                if (!isEmpty(i.fwds)) {
                    for (p in i.fwds) {
                        if (messages.contains(p)) {
                            messages.remove(p)
                        }
                    }
                }
            }
        }
        if (!isEmpty(messages)) {
            outConfig.models.append(FwdMessages(messages))
        }

        resolveAttachmentsCounter()
        resolvePrimaryButton()
    }

    fun fireForwardToAnotherClick(messages: ArrayList<Message>) {
        view?.forwardMessagesToAnotherConversation(messages, messagesOwnerId) // TODO: 15.08.2017
    }

    public override fun onActionModeForwardClick() {
        val selected = getSelected(data)
        if (selected.isNotEmpty()) {
            view?.diplayForwardTypeSelectDialog(selected)
        }
    }

    @OnGuiCreated
    private fun resolveOptionMenu() {
        val chat = isGroupChat

        var isPlusEncryption = false
        if (isEncryptionEnabled) {
            isPlusEncryption = Settings.get()
                    .security()
                    .getEncryptionLocationPolicy(messagesOwnerId, peerId) == KeyLocationPolicy.RAM
        }

        view?.configOptionMenu(chat, chat, chat, isEncryptionSupport, isEncryptionEnabled, isPlusEncryption, isEncryptionSupport, !HronoType, peerId < VKApiMessage.CHAT_PEER)
    }

    fun fireEncriptionStatusClick() {
        if (!isEncryptionEnabled && !Settings.get().security().isKeyEncryptionPolicyAccepted) {
            view?.showEncryptionDisclaimerDialog(REQUEST_CODE_ENABLE_ENCRYPTION)
            return
        }

        onEncryptionToggleClick()
    }

    private fun onEncryptionToggleClick() {
        if (isEncryptionEnabled) {
            Settings.get().security().disableMessageEncryption(messagesOwnerId, peerId)
            resolveOptionMenu()
        } else {
            view?.showEncryptionKeysPolicyChooseDialog(REQUEST_CODE_ENABLE_ENCRYPTION)
        }
    }

    private fun fireKeyStoreSelected(requestCode: Int, @KeyLocationPolicy policy: Int) {
        when (requestCode) {
            REQUEST_CODE_ENABLE_ENCRYPTION -> onEnableEncryptionKeyStoreSelected(policy)
            REQUEST_CODE_KEY_EXCHANGE -> KeyExchangeService.iniciateKeyExchangeSession(applicationContext, messagesOwnerId, peerId, policy)
        }
    }

    fun fireDiskKeyStoreSelected(requestCode: Int) {
        fireKeyStoreSelected(requestCode, KeyLocationPolicy.PERSIST)
    }

    fun fireRamKeyStoreSelected(requestCode: Int) {
        fireKeyStoreSelected(requestCode, KeyLocationPolicy.RAM)
    }

    private fun onEnableEncryptionKeyStoreSelected(@KeyLocationPolicy policy: Int) {
        appendDisposable(Stores.getInstance()
                .keys(policy)
                .getKeys(messagesOwnerId, peerId)
                .fromIOToMain()
                .subscribe({ aesKeyPairs -> fireEncriptionEnableClick(policy, aesKeyPairs) }, { Analytics.logUnexpectedError(it) }))
    }

    private fun fireEncriptionEnableClick(@KeyLocationPolicy policy: Int, pairs: List<AesKeyPair>) {
        if (pairs.nullOrEmpty()) {
            view?.displayIniciateKeyExchangeQuestion(policy)
        } else {
            Settings.get().security().enableMessageEncryption(messagesOwnerId, peerId, policy)
            resolveOptionMenu()
        }
    }

    fun fireIniciateKeyExchangeClick(@KeyLocationPolicy policy: Int) {
        KeyExchangeService.iniciateKeyExchangeSession(App.instance, messagesOwnerId, peerId, policy)
    }

    override fun saveState(outState: Bundle) {
        super.saveState(outState)
        outState.putParcelable(SAVE_PEER, peer)
        outState.putString(SAVE_DRAFT_MESSAGE_TEXT, draftMessageText)
        outState.putInt(SAVE_DRAFT_MESSAGE_ATTACHMENTS_COUNT, draftMessageDbAttachmentsCount)
        outState.putParcelable(SAVE_CONFIG, outConfig)
        outState.putParcelable(SAVE_CAMERA_FILE_URI, currentPhotoCameraUri)

        draftMessageId?.run {
            outState.putInt(SAVE_DRAFT_MESSAGE_ID, this)
        }
    }

    private fun restoreFromInstanceState(state: Bundle) {
        draftMessageText = state.getString(SAVE_DRAFT_MESSAGE_TEXT)
        draftMessageDbAttachmentsCount = state.getInt(SAVE_DRAFT_MESSAGE_ATTACHMENTS_COUNT)

        if (state.containsKey(SAVE_DRAFT_MESSAGE_ID)) {
            draftMessageId = state.getInt(SAVE_DRAFT_MESSAGE_ID)
        }
    }

    fun fireStickerSendClick(sticker: Sticker) {
        view?.ScrollTo(0)
        val builder = SaveMessageBuilder(messagesOwnerId, peerId).attach(sticker)
        sendMessage(builder)
    }

    fun fireBotSendClick(item: Keyboard.Button, context: Context) {
        if (item.type == "open_link") {
            LinkHelper.openLinkInBrowser(context, item.link)
            return
        }
        view?.ScrollTo(0)
        val builder = SaveMessageBuilder(messagesOwnerId, peerId).setPayload(item.payload).setBody(item.label)
        sendMessage(builder)
    }

    fun fireKeyExchangeClick() {
        if (!Settings.get().security().isKeyEncryptionPolicyAccepted) {
            view?.showEncryptionDisclaimerDialog(REQUEST_CODE_KEY_EXCHANGE)
            return
        }

        if (isEncryptionSupport) {
            view?.showEncryptionKeysPolicyChooseDialog(REQUEST_CODE_KEY_EXCHANGE)
        }
    }

    fun fireTermsOfUseAcceptClick(requestCode: Int) {
        Settings.get().security().isKeyEncryptionPolicyAccepted = true

        when (requestCode) {
            REQUEST_CODE_KEY_EXCHANGE -> if (isEncryptionSupport) {
                view?.showEncryptionKeysPolicyChooseDialog(REQUEST_CODE_KEY_EXCHANGE)
            }

            REQUEST_CODE_ENABLE_ENCRYPTION -> onEncryptionToggleClick()
        }
    }

    fun fireSendClickFromAttachmens() {
        fireSendClick()
    }

    private var edited: EditedMessage? = null

    fun fireActionModeEditClick() {
        val m = data.find {
            it.isSelected
        }

        edited = if (m != null) EditedMessage(m) else null

        resolveDraftMessageText()
        resolveAttachmentsCounter()
        resolveEditedMessageViews()
        resolvePrimaryButton()
    }

    private fun cancelMessageEditing(): Boolean {
        edited?.run {
            val destination = UploadDestination.forMessage(message.id)

            edited = null
            resolveDraftMessageText()
            resolveAttachmentsCounter()
            resolveEditedMessageViews()
            resolvePrimaryButton()

            uploadManager.cancelAll(accountId, destination)
            return true
        }

        return false
    }

    fun fireCancelEditingClick() {
        cancelMessageEditing()
    }

    fun onBackPressed(): Boolean {
        return !cancelMessageEditing()
    }

    fun fireEditMessageSaveClick() {
        edited?.run {
            val models = ArrayList<AbsModel>()
            var keepForward = false

            for (entry in attachments) {
                when (entry.attachment) {
                    is FwdMessages -> keepForward = true
                    is Upload -> {
                        view?.showError(R.string.upload_not_resolved_exception_message)
                        return
                    }
                    else -> models.add(entry.attachment)
                }
            }

            appendDisposable(messagesRepository.edit(accountId, message, body, models, keepForward)
                    .fromIOToMain()
                    .subscribe({ onMessageEdited(it) }, { t -> onMessageEditFail(t) }))
        }
    }

    private fun onMessageEditFail(throwable: Throwable) {
        showError(view, throwable)
    }

    private fun onMessageEdited(message: Message) {
        edited = null
        resolveAttachmentsCounter()
        resolveDraftMessageText()
        resolveEditedMessageViews()
        resolvePrimaryButton()

        val index = data.indexOfFirst {
            it.id == message.id
        }

        if (index != -1) {
            data[index] = message
            view?.notifyDataChanged()
        }
    }

    fun fireEditAttachmentRetry(entry: AttachmenEntry) {
        fireEditAttachmentRemoved(entry)
        if (entry.attachment is Upload) {
            val upl = entry.attachment as Upload
            val intents: MutableList<UploadIntent> = java.util.ArrayList()
            intents.add(UploadIntent(accountId, upl.destination)
                    .setSize(upl.size)
                    .setAutoCommit(upl.isAutoCommit)
                    .setFileId(upl.fileId)
                    .setFileUri(upl.fileUri))
            uploadManager.enqueue(intents)
        }
    }

    fun fireEditAttachmentRemoved(entry: AttachmenEntry) {
        if (entry.attachment is Upload) {
            uploadManager.cancel((entry.attachment as Upload).id)
            return
        }

        edited?.run {
            val index = attachments.indexOf(entry)
            if (index != -1) {
                attachments.removeAt(index)
                view?.notifyEditAttachmentRemoved(index)
                resolveAttachmentsCounter()
                resolvePrimaryButton()
            }
        }
    }

    fun fireEditAddImageClick() {
        view?.startImagesSelection(accountId, messagesOwnerId)
    }

    fun fireLongAvatarClick(Id: Int) {
        if (Id > 0) {
            netLoadingDisposable = Repository.owners.getFullUserInfo(accountId, Id, MODE_NET)
                    .fromIOToMain()
                    .subscribe({ info ->
                        run {
                            val Dmn: String = if (info.first.domain == null)
                                "@id$Id,"
                            else
                                "@" + info.first.domain + ","
                            view?.AppendMessageText(Dmn)
                        }
                    }, { })
        }
    }

    fun fireFilePhotoForUploadSelected(file: String?, imageSize: Int) {
        edited?.run {
            val destination = UploadDestination.forMessage(message.id)
            val intent = UploadIntent(accountId, destination)
                    .setAutoCommit(false)
                    .setFileUri(Uri.parse(file)).setSize(imageSize)
            uploadManager.enqueue(listOf(intent))
        }
    }

    fun fireFileVideoForUploadSelected(file: String?) {
        edited?.run {
            val destination = UploadDestination.forMessage(message.id, MessageMethod.VIDEO)
            val intent = UploadIntent(accountId, destination)
                    .setAutoCommit(false)
                    .setFileUri(Uri.parse(file))
            uploadManager.enqueue(listOf(intent))
        }
    }

    fun fireEditLocalPhotosSelected(localPhotos: List<LocalPhoto>, imageSize: Int) {
        edited?.run {
            if (localPhotos.isNotEmpty()) {
                val destination = UploadDestination.forMessage(message.id)

                val intents = localPhotos.map {
                    UploadIntent(accountId, destination).apply {
                        isAutoCommit = false
                        fileId = it.imageId
                        fileUri = it.fullImageUri
                        size = imageSize
                    }
                }

                uploadManager.enqueue(intents)
            }
        }
    }

    fun fireEditLocalVideoSelected(video: LocalVideo) {
        edited?.run {
            val destination = UploadDestination.forMessage(message.id, MessageMethod.VIDEO)

            val intents = UploadIntent(accountId, destination).apply {
                isAutoCommit = false
                fileUri = Uri.parse(video.data.toString())
            }

            uploadManager.enqueue(Collections.singletonList(intents))
        }
    }

    fun fireEditAttachmentsSelected(models: List<AbsModel>) {
        edited?.run {
            if (models.isNotEmpty()) {
                val additional = models.map {
                    AttachmenEntry(true, it)
                }

                val sizeBefore = attachments.size
                attachments.addAll(additional)
                view?.notifyEditAttachmentsAdded(sizeBefore, additional.size)
                resolveAttachmentsCounter()
                resolvePrimaryButton()
            }
        }
    }

    fun fireUnpinClick() {
        doPin(null)
    }

    private fun doPin(message: Message?) {
        appendDisposable(messagesRepository.pin(accountId, peerId, message)
                .fromIOToMain()
                .subscribe(dummy(), { onPinFail(it) }))
    }

    fun fireActionModePinClick() {
        val message = data.find { it.isSelected }
        doPin(message)
    }

    private fun onPinFail(throwable: Throwable) {
        showError(view, throwable)
    }

    fun onEditAddVideoClick() {
        view?.startVideoSelection(accountId, messagesOwnerId)
    }

    fun onEditAddAudioClick() {
        view?.startAudioSelection(accountId)
    }

    fun onEditAddDocClick() {
        view?.startDocSelection(accountId)
    }

    fun fireEditCameraClick() {
        try {
            val file = FileUtil.createImageFile()
            currentPhotoCameraUri = FileUtil.getExportedUriForFile(applicationContext, file)
            currentPhotoCameraUri?.run {
                view?.startCamera(this)
            }
        } catch (e: IOException) {
            safeShowError(view, e.message)
        }
    }

    @Suppress("DEPRECATION")
    fun fireEditPhotoMaked(size: Int) {
        val uri = currentPhotoCameraUri
        currentPhotoCameraUri = null

        val scanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri)
        applicationContext.sendBroadcast(scanIntent)

        val makedPhoto = LocalPhoto().setFullImageUri(uri)
        fireEditLocalPhotosSelected(listOf(makedPhoto), size)
    }

    fun fireDeleteForAllClick(ids: ArrayList<Int>) {
        deleteSentImpl(ids, true)
    }

    fun fireDeleteForMeClick(ids: ArrayList<Int>) {
        deleteSentImpl(ids, false)
    }

    fun fireScrollToEnd() {
        if (canLoadMore()) {
            requestMore()
        }
    }

    private class ToolbarSubtitleHandler(prensenter: ChatPrensenter) : Handler(Looper.getMainLooper()) {

        var reference: WeakReference<ChatPrensenter> = WeakReference(prensenter)

        override fun handleMessage(msg: android.os.Message) {
            reference.get()?.run {
                when (msg.what) {
                    RESTORE_TOLLBAR -> hideWriting()
                }
            }
        }

        fun release() {
            removeMessages(RESTORE_TOLLBAR)
        }

        fun restoreToolbarWithDelay() {
            sendEmptyMessageDelayed(RESTORE_TOLLBAR, 3000)
        }

        companion object {
            const val RESTORE_TOLLBAR = 12
        }
    }

    companion object {

        private const val COUNT = 30
        private const val RECORD_EXT_MP3 = "mp3"

        private const val SAVE_PEER = "save_peer"
        private const val SAVE_DRAFT_MESSAGE_TEXT = "save_draft_message_text"
        private const val SAVE_DRAFT_MESSAGE_ATTACHMENTS_COUNT = "save_draft_message_attachments_count"
        private const val SAVE_DRAFT_MESSAGE_ID = "save_draft_message_id"
        private const val SAVE_CONFIG = "save_config"
        private const val SAVE_CAMERA_FILE_URI = "save_camera_file_uri"

        private const val REQUEST_CODE_ENABLE_ENCRYPTION = 1
        private const val REQUEST_CODE_KEY_EXCHANGE = 2

        private val MESSAGES_COMPARATOR = Comparator<Message> { rhs, lhs ->
            // соблюдаем сортировку как при запросе в бд

            if (lhs.status == rhs.status) {
                return@Comparator lhs.id.compareTo(rhs.id)
            }

            lhs.status.compareTo(rhs.status)
        }
    }
}
