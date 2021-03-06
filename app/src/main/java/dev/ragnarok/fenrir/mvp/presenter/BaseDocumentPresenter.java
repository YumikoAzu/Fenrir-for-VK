package dev.ragnarok.fenrir.mvp.presenter;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.ragnarok.fenrir.R;
import dev.ragnarok.fenrir.domain.IDocsInteractor;
import dev.ragnarok.fenrir.domain.InteractorFactory;
import dev.ragnarok.fenrir.model.Document;
import dev.ragnarok.fenrir.mvp.presenter.base.AccountDependencyPresenter;
import dev.ragnarok.fenrir.mvp.view.IBasicDocumentView;
import dev.ragnarok.fenrir.util.RxUtils;

import static dev.ragnarok.fenrir.util.Utils.getCauseIfRuntime;


public class BaseDocumentPresenter<V extends IBasicDocumentView> extends AccountDependencyPresenter<V> {

    private static final String TAG = BaseDocumentPresenter.class.getSimpleName();

    private final IDocsInteractor docsInteractor;

    public BaseDocumentPresenter(int accountId, @Nullable Bundle savedInstanceState) {
        super(accountId, savedInstanceState);
        docsInteractor = InteractorFactory.createDocsInteractor();
    }

    public final void fireWritePermissionResolved() {
        onWritePermissionResolved();
    }

    protected void onWritePermissionResolved() {
        // hook for child classes
    }

    protected void addYourself(@NonNull Document document) {
        int accountId = getAccountId();
        int docId = document.getId();
        int ownerId = document.getOwnerId();

        appendDisposable(docsInteractor.add(accountId, docId, ownerId, document.getAccessKey())
                .compose(RxUtils.applySingleIOToMainSchedulers())
                .subscribe(id -> onDocAddedSuccessfully(docId, ownerId, id), t -> showError(getView(), getCauseIfRuntime(t))));
    }

    protected void delete(int id, int ownerId) {
        int accountId = getAccountId();
        appendDisposable(docsInteractor.delete(accountId, id, ownerId)
                .compose(RxUtils.applyCompletableIOToMainSchedulers())
                .subscribe(() -> onDocDeleteSuccessfully(id, ownerId), this::onDocDeleteError));
    }

    private void onDocDeleteError(Throwable t) {
        showError(getView(), getCauseIfRuntime(t));
    }

    @SuppressWarnings("unused")
    protected void onDocDeleteSuccessfully(int id, int ownerId) {
        safeShowLongToast(getView(), R.string.deleted);
    }

    @SuppressWarnings("unused")
    protected void onDocAddedSuccessfully(int id, int ownerId, int resultDocId) {
        safeShowLongToast(getView(), R.string.added);
    }
}