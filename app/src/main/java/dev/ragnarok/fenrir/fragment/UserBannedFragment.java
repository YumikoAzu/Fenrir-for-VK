package dev.ragnarok.fenrir.fragment;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.ragnarok.fenrir.Extra;
import dev.ragnarok.fenrir.R;
import dev.ragnarok.fenrir.activity.ActivityFeatures;
import dev.ragnarok.fenrir.activity.ActivityUtils;
import dev.ragnarok.fenrir.activity.SelectProfilesActivity;
import dev.ragnarok.fenrir.adapter.PeopleAdapter;
import dev.ragnarok.fenrir.fragment.base.BaseMvpFragment;
import dev.ragnarok.fenrir.fragment.friends.FriendsTabsFragment;
import dev.ragnarok.fenrir.listener.EndlessRecyclerOnScrollListener;
import dev.ragnarok.fenrir.listener.OnSectionResumeCallback;
import dev.ragnarok.fenrir.model.Owner;
import dev.ragnarok.fenrir.model.SelectProfileCriteria;
import dev.ragnarok.fenrir.model.User;
import dev.ragnarok.fenrir.mvp.core.IPresenterFactory;
import dev.ragnarok.fenrir.mvp.presenter.UserBannedPresenter;
import dev.ragnarok.fenrir.mvp.view.IUserBannedView;
import dev.ragnarok.fenrir.place.Place;
import dev.ragnarok.fenrir.place.PlaceFactory;
import dev.ragnarok.fenrir.util.AssertUtils;
import dev.ragnarok.fenrir.util.ViewUtils;

import static dev.ragnarok.fenrir.util.Objects.nonNull;

public class UserBannedFragment extends BaseMvpFragment<UserBannedPresenter, IUserBannedView> implements IUserBannedView, PeopleAdapter.LongClickListener {

    private static final int REQUEST_SELECT = 13;
    private SwipeRefreshLayout mSwipeRefreshLayout;
    private RecyclerView mRecyclerView;
    private PeopleAdapter mPeopleAdapter;
    private TextView mEmptyText;

    public static UserBannedFragment newInstance(int accountId) {
        Bundle args = new Bundle();
        args.putInt(Extra.ACCOUNT_ID, accountId);
        UserBannedFragment fragment = new UserBannedFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_user_banned, container, false);
        ((AppCompatActivity) requireActivity()).setSupportActionBar(root.findViewById(R.id.toolbar));

        mSwipeRefreshLayout = root.findViewById(R.id.refresh);
        mSwipeRefreshLayout.setOnRefreshListener(() -> getPresenter().fireRefresh());
        ViewUtils.setupSwipeRefreshLayoutWithCurrentTheme(requireActivity(), mSwipeRefreshLayout);

        mRecyclerView = root.findViewById(R.id.recycler_view);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(requireActivity()));
        mRecyclerView.addOnScrollListener(new EndlessRecyclerOnScrollListener() {
            @Override
            public void onScrollToLastElement() {
                getPresenter().fireScrollToEnd();
            }
        });

        mPeopleAdapter = new PeopleAdapter(requireActivity(), Collections.emptyList());
        mPeopleAdapter.setLongClickListener(this);
        mPeopleAdapter.setClickListener(owner -> getPresenter().fireUserClick((User) owner));
        mRecyclerView.setAdapter(mPeopleAdapter);

        mEmptyText = root.findViewById(R.id.empty_text);

        root.findViewById(R.id.button_add).setOnClickListener(v -> getPresenter().fireButtonAddClick());

        resolveEmptyTextVisibility();
        return root;
    }

    private void resolveEmptyTextVisibility() {
        if (nonNull(mPeopleAdapter) && nonNull(mEmptyText)) {
            mEmptyText.setVisibility(mPeopleAdapter.getItemCount() > 0 ? View.GONE : View.VISIBLE);
        }
    }

    @Override
    public void displayUserList(List<User> users) {
        if (nonNull(mPeopleAdapter)) {
            mPeopleAdapter.setItems(users);
            resolveEmptyTextVisibility();
        }
    }

    @Override
    public void notifyItemsAdded(int position, int count) {
        if (nonNull(mPeopleAdapter)) {
            mPeopleAdapter.notifyItemRangeInserted(position, count);
            resolveEmptyTextVisibility();
        }
    }

    @Override
    public void notifyDataSetChanged() {
        if (nonNull(mPeopleAdapter)) {
            mPeopleAdapter.notifyDataSetChanged();
            resolveEmptyTextVisibility();
        }
    }

    @Override
    public void notifyItemRemoved(int position) {
        if (nonNull(mPeopleAdapter)) {
            mPeopleAdapter.notifyItemRemoved(position);
            resolveEmptyTextVisibility();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if (requireActivity() instanceof OnSectionResumeCallback) {
            ((OnSectionResumeCallback) requireActivity()).onClearSelection();
        }

        ActionBar actionBar = ActivityUtils.supportToolbarFor(this);
        if (actionBar != null) {
            actionBar.setTitle(R.string.user_blacklist_title);
            actionBar.setSubtitle(null);
        }

        new ActivityFeatures.Builder()
                .begin()
                .setHideNavigationMenu(false)
                .setBarsColored(requireActivity(), true)
                .build()
                .apply(requireActivity());
    }

    @Override
    public void displayRefreshing(boolean refreshing) {
        if (nonNull(mSwipeRefreshLayout)) {
            mSwipeRefreshLayout.setRefreshing(refreshing);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SELECT && resultCode == Activity.RESULT_OK) {
            ArrayList<Owner> users = data.getParcelableArrayListExtra(Extra.OWNERS);
            AssertUtils.requireNonNull(users);
            postPrenseterReceive(presenter -> presenter.fireUsersSelected(users));
        }
    }

    @Override
    public void startUserSelection(int accountId) {
        Place place = PlaceFactory.getFriendsFollowersPlace(accountId, accountId, FriendsTabsFragment.TAB_ALL_FRIENDS, null);
        SelectProfileCriteria criteria = new SelectProfileCriteria();
        Intent intent = SelectProfilesActivity.createIntent(requireActivity(), place, criteria);
        startActivityForResult(intent, REQUEST_SELECT);
    }

    @Override
    public void showSuccessToast() {
        Toast.makeText(requireActivity(), R.string.success, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void scrollToPosition(int position) {
        if (nonNull(mRecyclerView)) {
            mRecyclerView.smoothScrollToPosition(position);
        }
    }

    @Override
    public void showUserProfile(int accountId, User user) {
        PlaceFactory.getOwnerWallPlace(accountId, user).tryOpenWith(requireActivity());
    }

    @NotNull
    @Override
    public IPresenterFactory<UserBannedPresenter> getPresenterFactory(@Nullable Bundle saveInstanceState) {
        return () -> new UserBannedPresenter(
                requireArguments().getInt(Extra.ACCOUNT_ID),
                saveInstanceState
        );
    }

    @Override
    public boolean onOwnerLongClick(Owner owner) {
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(owner.getFullName())
                .setItems(new String[]{getString(R.string.delete)}, (dialog, which) -> getPresenter().fireRemoveClick((User) owner))
                .setNegativeButton(R.string.button_cancel, null)
                .show();
        return true;
    }
}