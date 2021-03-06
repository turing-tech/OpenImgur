package com.kenny.openimgur.fragments;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.ActionBarActivity;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import com.kenny.openimgur.R;
import com.kenny.openimgur.activities.ViewActivity;
import com.kenny.openimgur.adapters.GalleryAdapter;
import com.kenny.openimgur.api.ApiClient;
import com.kenny.openimgur.api.Endpoints;
import com.kenny.openimgur.api.ImgurBusEvent;
import com.kenny.openimgur.classes.CustomLinkMovement;
import com.kenny.openimgur.classes.FragmentListener;
import com.kenny.openimgur.classes.ImgurAlbum;
import com.kenny.openimgur.classes.ImgurBaseObject;
import com.kenny.openimgur.classes.ImgurHandler;
import com.kenny.openimgur.classes.ImgurListener;
import com.kenny.openimgur.classes.ImgurPhoto;
import com.kenny.openimgur.classes.ImgurUser;
import com.kenny.openimgur.ui.HeaderGridView;
import com.kenny.openimgur.ui.MultiStateView;
import com.kenny.openimgur.ui.VideoView;
import com.kenny.openimgur.util.LinkUtils;
import com.kenny.openimgur.util.LogUtil;
import com.kenny.openimgur.util.ViewUtils;
import com.kenny.snackbar.SnackBar;
import com.nostra13.universalimageloader.core.listener.PauseOnScrollListener;

import org.apache.commons.collections15.list.SetUniqueList;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import butterknife.InjectView;
import de.greenrobot.event.EventBus;
import de.greenrobot.event.util.ThrowableFailureEvent;

/**
 * Created by kcampagna on 8/18/14.
 */
public class ProfileFragment extends BaseFragment implements ImgurListener {
    private static final String KEY_ENDPOINT = "endpoint";

    private static final String KEY_CURRENT_POSITION = "position";

    private static final String KEY_ITEMS = "items";

    private static final String KEY_CURRENT_PAGE = "page";

    private static final String KEY_USERNAME = "username";

    @InjectView(R.id.multiView)
    MultiStateView mMultiView;

    @InjectView(R.id.grid)
    HeaderGridView mGridView;

    @InjectView(R.id.loginWebView)
    WebView mWebView;

    private int mCurrentPage = 0;

    private Endpoints mCurrentEndpoint = Endpoints.ACCOUNT_GALLERY_FAVORITES;

    private GalleryAdapter mAdapter;

    private ImgurUser mSelectedUser;

    private ApiClient mApiClient;

    private FragmentListener mListener;

    private boolean mIsLoading = false;

    private boolean mHasMore = true;

    private boolean mDidAddProfileToErrorView = false;

    private int mPreviousItem;

    public static ProfileFragment createInstance(@Nullable String userName) {
        ProfileFragment fragment = new ProfileFragment();
        Bundle args = new Bundle();

        if (!TextUtils.isEmpty(userName)) {
            args.putString(KEY_USERNAME, userName);
        }

        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        if (activity instanceof FragmentListener) {
            mListener = (FragmentListener) activity;
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mListener = null;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.profile_fragment, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mGridView.setOnScrollListener(new PauseOnScrollListener(app.getImageLoader(), false, true,
                new AbsListView.OnScrollListener() {
                    @Override
                    public void onScrollStateChanged(AbsListView view, int scrollState) {

                    }

                    @Override
                    public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                        if (firstVisibleItem > mPreviousItem) {
                            if (mListener != null) {
                                mListener.onUpdateActionBar(false);
                            }
                        } else if (firstVisibleItem < mPreviousItem) {
                            if (mListener != null) {
                                mListener.onUpdateActionBar(true);
                            }
                        }

                        mPreviousItem = firstVisibleItem;

                        // Load more items when hey get to the end of the list
                        if (totalItemCount > 0 && firstVisibleItem + visibleItemCount >= totalItemCount && !mIsLoading && mHasMore) {
                            mIsLoading = true;
                            mCurrentPage++;
                            getGalleryData();
                        }
                    }
                }
        ));
        mGridView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int position, long l) {
                int headerSize = mGridView.getNumColumns() * mGridView.getHeaderViewCount();
                int adapterPosition = position - headerSize;
                // Don't respond to the header being clicked
                if (adapterPosition >= 0) {
                    ArrayList<ImgurBaseObject> items = mAdapter.getItems(adapterPosition);
                    int itemPosition = adapterPosition;

                    // Get the correct array index of the selected item
                    if (itemPosition > GalleryAdapter.MAX_ITEMS / 2) {
                        itemPosition = items.size() == GalleryAdapter.MAX_ITEMS ? GalleryAdapter.MAX_ITEMS / 2 :
                                items.size() - (mAdapter.getCount() - itemPosition);
                    }

                    startActivity(ViewActivity.createIntent(getActivity(), items, itemPosition));
                }
            }
        });

        handleBundle(savedInstanceState, getArguments());
    }

    /**
     * Handles the arguments past to the fragment
     *
     * @param args
     */
    private void handleArguments(Bundle args) {
        if (args.containsKey(KEY_USERNAME)) {
            LogUtil.v(TAG, "User present in Bundle extras");
            String username = args.getString(KEY_USERNAME);
            mSelectedUser = app.getSql().getUser(username);
            if (mListener != null) mListener.onUpdateActionBarTitle(username);
            configUser(username);
        } else if (app.getUser() != null) {
            LogUtil.v(TAG, "User already logged in");
            mSelectedUser = app.getUser();
            if (mListener != null) mListener.onUpdateActionBarTitle(mSelectedUser.getUsername());
            configUser(null);
        } else {
            LogUtil.v(TAG, "No user present. Showing Login screen");
            if (mListener != null) mListener.onUpdateActionBarTitle(getString(R.string.login));
            configWebView();
        }
    }

    private void handleBundle(Bundle savedInstanceState, Bundle args) {
        if (savedInstanceState == null) {
            handleArguments(args);
        } else {
            mCurrentPage = savedInstanceState.getInt(KEY_CURRENT_PAGE, 0);

            if (savedInstanceState.containsKey(KEY_ITEMS) && savedInstanceState.containsKey(KEY_USERNAME)) {
                mSelectedUser = savedInstanceState.getParcelable(KEY_USERNAME);
                mCurrentEndpoint = savedInstanceState.getString(KEY_ENDPOINT, null).equals(Endpoints.ACCOUNT_GALLERY_FAVORITES.getUrl()) ? Endpoints.ACCOUNT_GALLERY_FAVORITES : Endpoints.ACCOUNT_SUBMISSIONS;
                ArrayList<ImgurBaseObject> items = savedInstanceState.getParcelableArrayList(KEY_ITEMS);
                int currentPosition = savedInstanceState.getInt(KEY_CURRENT_POSITION, 0);
                mAdapter = new GalleryAdapter(getActivity(), SetUniqueList.decorate(items));
                mGridView.addHeaderView(ViewUtils.getHeaderViewForTranslucentStyle(getActivity(), 0));
                mGridView.addHeaderView(ViewUtils.getProfileView(mSelectedUser, getActivity(), mMultiView, ProfileFragment.this));
                mGridView.setAdapter(mAdapter);
                mGridView.setSelection(currentPosition);

                if (mListener != null) {
                    mListener.onLoadingComplete();
                    mListener.onUpdateActionBarTitle(mSelectedUser.getUsername());
                }
            } else {
                handleArguments(args);
            }
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.profile, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.favorites:
            case R.id.submissions:
                mCurrentPage = 0;
                mCurrentEndpoint = item.getItemId() == R.id.favorites ? Endpoints.ACCOUNT_GALLERY_FAVORITES : Endpoints.ACCOUNT_SUBMISSIONS;

                if (mAdapter != null) {
                    mAdapter.clear();
                }

                getActivity().invalidateOptionsMenu();
                getGalleryData();
                mMultiView.setViewState(MultiStateView.ViewState.LOADING);
                return true;

            case R.id.logout:
                new PopupDialogViewBuilder(getActivity())
                        .setTitle(R.string.logout)
                        .setMessage(R.string.logout_confirm)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.yes, new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                mSelectedUser = null;
                                app.onLogout();
                                getActivity().invalidateOptionsMenu();

                                if (mAdapter != null) {
                                    mAdapter.clear();
                                }

                                configWebView();
                                mMultiView.setViewState(MultiStateView.ViewState.EMPTY);
                                if (mListener != null) {
                                    mListener.onUpdateActionBarTitle(getString(R.string.login));
                                    mListener.onUpdateUser(null);
                                }
                            }
                        }).show();
                return true;

            case R.id.refresh:
                // Force the app to fetch new data for the user
                String username = mSelectedUser.getUsername();
                mSelectedUser = null;
                configUser(username);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        if (menu.hasVisibleItems()) {
            if (mSelectedUser == null) {
                menu.findItem(R.id.favorites).setVisible(false);
                menu.findItem(R.id.submissions).setVisible(false);
                menu.findItem(R.id.logout).setVisible(false);
                menu.findItem(R.id.refresh).setVisible(false);
            } else {
                menu.findItem(R.id.logout).setVisible(mSelectedUser.isSelf());
                menu.findItem(R.id.favorites).setVisible(mCurrentEndpoint == Endpoints.ACCOUNT_SUBMISSIONS);
                menu.findItem(R.id.submissions).setVisible(mCurrentEndpoint == Endpoints.ACCOUNT_GALLERY_FAVORITES);
            }
        }

        super.onPrepareOptionsMenu(menu);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this);
        }

        if (mDidAddProfileToErrorView || mAdapter != null) {
            CustomLinkMovement.getInstance().addListener(ProfileFragment.this);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        CustomLinkMovement.getInstance().removeListener(ProfileFragment.this);
    }

    @Override
    public void onDestroyView() {
        EventBus.getDefault().unregister(this);
        mWebView = null;
        mMultiView = null;
        mGridView = null;
        mHandler.removeCallbacksAndMessages(null);

        if (mAdapter != null) {
            mAdapter.clear();
            mAdapter = null;
        }

        super.onDestroyView();
    }

    /**
     * Configures the webview to handle a user logging in
     */
    private void configWebView() {
        if (mListener != null) {
            mListener.onLoadingStarted();
        }

        ((ActionBarActivity) getActivity()).getSupportActionBar().show();
        mMultiView.setViewState(MultiStateView.ViewState.EMPTY);
        // Add the empty space so the webview isnt cut off by the action bar
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mWebView.getLayoutParams();
        lp.setMargins(0, ViewUtils.getHeightForTranslucentStyle(getActivity()), 0, 0);
        mWebView.loadUrl(Endpoints.LOGIN.getUrl());
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.contains("#")) {
                    // We will extract the info from the callback url
                    mMultiView.setViewState(MultiStateView.ViewState.LOADING);
                    if (mListener != null) mListener.onLoadingStarted();
                    String[] outerSplit = url.split("\\#")[1].split("\\&");
                    String username = null;
                    String accessToken = null;
                    String refreshToken = null;
                    long accessTokenExpiration = 0;
                    int index = 0;

                    for (String s : outerSplit) {
                        String[] innerSplit = s.split("\\=");

                        switch (index) {
                            // Access Token
                            case 0:
                                accessToken = innerSplit[1];
                                break;

                            // Access Token Expiration
                            case 1:
                                long expiresIn = Long.parseLong(innerSplit[1]);
                                accessTokenExpiration = System.currentTimeMillis() + (expiresIn * DateUtils.SECOND_IN_MILLIS);
                                break;

                            // Token Type, not using
                            case 2:
                                //NO OP
                                break;

                            // Refresh Token
                            case 3:
                                refreshToken = innerSplit[1];
                                break;

                            // Username
                            case 4:
                                username = innerSplit[1];
                                break;
                        }

                        index++;
                    }

                    // Make sure that everything was set
                    if (!TextUtils.isEmpty(username) && !TextUtils.isEmpty(accessToken) &&
                            !TextUtils.isEmpty(refreshToken) && accessTokenExpiration > 0) {
                        ImgurUser newUser = new ImgurUser(username, accessToken, refreshToken, accessTokenExpiration);
                        app.setUser(newUser);
                        user = newUser;
                        mSelectedUser = newUser;
                        LogUtil.v(TAG, "User " + newUser.getUsername() + " logged in");
                        String detailsUrls = String.format(Endpoints.PROFILE.getUrl(), newUser.getUsername());
                        mApiClient = new ApiClient(detailsUrls, ApiClient.HttpRequest.GET);
                        mApiClient.doWork(ImgurBusEvent.EventType.PROFILE_DETAILS, null, null);
                        CookieManager.getInstance().removeAllCookie();
                        mWebView.clearHistory();
                        mWebView.clearCache(true);
                        mWebView.clearFormData();
                        if (mListener != null) {
                            mListener.onUpdateActionBarTitle(mSelectedUser.getUsername());
                        }
                    } else {
                        mHandler.sendMessage(ImgurHandler.MESSAGE_ACTION_FAILED, R.string.error_generic);
                    }

                } else {
                    // Didn't get our tokens from the response, they probably denied accessed, just reshow the login page
                    LogUtil.w(TAG, "URL didn't contain a '#'. User denied access");
                    mWebView.loadUrl(Endpoints.LOGIN.getUrl());
                }

                return true;
            }
        });
    }

    /**
     * Checks the database if there is cached data for the user and whether data should be loaded if it is old
     *
     * @param username
     */
    private void configUser(String username) {
        mMultiView.setViewState(MultiStateView.ViewState.LOADING);
        // Load the new user data if we haven't viewed the user within 24 hours
        if (mSelectedUser == null || System.currentTimeMillis() - mSelectedUser.getLastSeen() >= DateUtils.DAY_IN_MILLIS) {
            LogUtil.v(TAG, "Selected user is null or data is too old, fetching new data");
            String detailsUrls = String.format(Endpoints.PROFILE.getUrl(), mSelectedUser == null ? username : mSelectedUser.getUsername());

            if (mApiClient == null) {
                mApiClient = new ApiClient(detailsUrls, ApiClient.HttpRequest.GET);
            } else {
                mApiClient.setRequestType(ApiClient.HttpRequest.GET);
                mApiClient.setUrl(detailsUrls);
            }

            mIsLoading = true;
            mApiClient.doWork(ImgurBusEvent.EventType.PROFILE_DETAILS, null, null);
        } else {
            LogUtil.v(TAG, "Selected user present in database and has valid data, fetching gallery");
            getGalleryData();
        }
    }

    /**
     * Gets either the users favorites or submissions passed on the current selection
     */
    private void getGalleryData() {
        String url;
        if (mCurrentEndpoint == Endpoints.ACCOUNT_GALLERY_FAVORITES) {
            url = String.format(mCurrentEndpoint.getUrl(), mSelectedUser.getUsername());
        } else {
            url = String.format(mCurrentEndpoint.getUrl(), mSelectedUser.getUsername(), mCurrentPage);
        }

        if (mApiClient == null) {
            mApiClient = new ApiClient(url, ApiClient.HttpRequest.GET);
        } else {
            mApiClient.setRequestType(ApiClient.HttpRequest.GET);
            mApiClient.setUrl(url);
        }

        mIsLoading = true;
        mApiClient.doWork(ImgurBusEvent.EventType.ACCOUNT_GALLERY_FAVORITES, null, null);
    }

    public void onEventAsync(@NonNull ImgurBusEvent event) {
        if (isResumed()) {
            try {
                int status = event.json.getInt(ApiClient.KEY_STATUS);

                switch (event.eventType) {
                    case PROFILE_DETAILS:
                        if (status == ApiClient.STATUS_OK) {
                            if (mSelectedUser == null) {
                                mSelectedUser = new ImgurUser(event.json);
                            } else {
                                mSelectedUser.parseJsonForValues(event.json);
                            }

                            if (mSelectedUser.isSelf()) {
                                app.getSql().updateUserInfo(mSelectedUser);
                            } else {
                                app.getSql().insertProfile(mSelectedUser);
                            }

                            getGalleryData();
                        } else {
                            mHandler.sendMessage(ImgurHandler.MESSAGE_ACTION_FAILED, ApiClient.getErrorCodeStringResource(status));
                        }
                        break;

                    case ACCOUNT_GALLERY_FAVORITES:
                    case ACCOUNT_SUBMISSIONS:
                        if (status == ApiClient.STATUS_OK) {
                            List<ImgurBaseObject> objects = new ArrayList<ImgurBaseObject>();
                            JSONArray arr = event.json.getJSONArray(ApiClient.KEY_DATA);

                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject item = arr.getJSONObject(i);

                                if (item.has("is_album") && item.getBoolean("is_album")) {
                                    ImgurAlbum a = new ImgurAlbum(item);
                                    objects.add(a);
                                } else {
                                    ImgurPhoto p = new ImgurPhoto(item);
                                    objects.add(p);
                                }
                            }

                            mHandler.sendMessage(ImgurHandler.MESSAGE_ACTION_COMPLETE, objects);
                        } else {
                            mHandler.sendMessage(ImgurHandler.MESSAGE_ACTION_FAILED, ApiClient.getErrorCodeStringResource(status));
                        }
                        break;
                }

            } catch (JSONException e) {
                LogUtil.e(TAG, "Error decoding JSON", e);
                mHandler.sendMessage(ImgurHandler.MESSAGE_ACTION_FAILED, ApiClient.getErrorCodeStringResource(ApiClient.STATUS_JSON_EXCEPTION));
            }
        }
    }

    public void onEventMainThread(ThrowableFailureEvent event) {
        Throwable e = event.getThrowable();

        if (e instanceof IOException) {
            mHandler.sendMessage(ImgurHandler.MESSAGE_ACTION_FAILED, ApiClient.getErrorCodeStringResource(ApiClient.STATUS_IO_EXCEPTION));
        } else if (e instanceof JSONException) {
            mHandler.sendMessage(ImgurHandler.MESSAGE_ACTION_FAILED, ApiClient.getErrorCodeStringResource(ApiClient.STATUS_JSON_EXCEPTION));
        } else {
            mHandler.sendMessage(ImgurHandler.MESSAGE_ACTION_FAILED, ApiClient.getErrorCodeStringResource(ApiClient.STATUS_INTERNAL_ERROR));
        }

        LogUtil.e(TAG, "Error received from Event Bus", e);

    }

    private ImgurHandler mHandler = new ImgurHandler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case ImgurHandler.MESSAGE_ACTION_COMPLETE:
                    if (mListener != null) {
                        mListener.onLoadingComplete();
                        if (mSelectedUser.isSelf()) mListener.onUpdateUser(mSelectedUser);
                    }

                    List<ImgurBaseObject> objects = (List<ImgurBaseObject>) msg.obj;
                    mHasMore = !objects.isEmpty() && mCurrentEndpoint == Endpoints.ACCOUNT_SUBMISSIONS;

                    if (objects.size() > 0) {
                        if (mAdapter == null) {
                            mAdapter = new GalleryAdapter(getActivity(), SetUniqueList.decorate(objects));
                            mGridView.addHeaderView(ViewUtils.getHeaderViewForTranslucentStyle(getActivity(), 0));
                            mGridView.addHeaderView(ViewUtils.getProfileView(mSelectedUser, getActivity(), mMultiView, ProfileFragment.this));
                            mGridView.setAdapter(mAdapter);
                        } else {
                            mAdapter.addItems(objects);
                        }
                    } else if (mAdapter == null || mAdapter.isEmpty()) {
                        if (!mDidAddProfileToErrorView) {
                            mDidAddProfileToErrorView = true;
                            LinearLayout container = (LinearLayout) mMultiView.getView(MultiStateView.ViewState.ERROR).findViewById(R.id.container);
                            container.addView(ViewUtils.getProfileView(mSelectedUser, getActivity(), mMultiView, ProfileFragment.this), 0);
                            container.addView(ViewUtils.getHeaderViewForTranslucentStyle(getActivity(), 0), 0);
                        }

                        String errorMessage = mCurrentEndpoint == Endpoints.ACCOUNT_SUBMISSIONS ?
                                getString(R.string.profile_no_submissions, mSelectedUser.getUsername()) :
                                getString(R.string.profile_no_favorites, mSelectedUser.getUsername());

                        mMultiView.setErrorText(R.id.errorMessage, errorMessage);
                    }

                    getActivity().invalidateOptionsMenu();
                    mMultiView.setViewState(mAdapter == null || mAdapter.isEmpty() ? MultiStateView.ViewState.ERROR : MultiStateView.ViewState.CONTENT);
                    if (mCurrentEndpoint == Endpoints.ACCOUNT_SUBMISSIONS && mCurrentPage == 0) {
                        mMultiView.post(new Runnable() {
                            @Override
                            public void run() {
                                mGridView.setSelection(0);
                            }
                        });
                    }

                    break;

                case ImgurHandler.MESSAGE_ACTION_FAILED:
                    if (mAdapter == null || mAdapter.isEmpty()) {
                        if (mListener != null) {
                            mListener.onError((Integer) msg.obj);
                        }

                        if (!mDidAddProfileToErrorView && mSelectedUser != null) {
                            mDidAddProfileToErrorView = true;
                            LinearLayout container = (LinearLayout) mMultiView.getView(MultiStateView.ViewState.EMPTY).findViewById(R.id.container);
                            container.addView(ViewUtils.getProfileView(mSelectedUser, getActivity(), mMultiView, ProfileFragment.this), 0);
                            container.addView(ViewUtils.getHeaderViewForTranslucentStyle(getActivity(), 0), 0);
                        }

                        mMultiView.setErrorText(R.id.errorMessage, (Integer) msg.obj);
                        mMultiView.setViewState(MultiStateView.ViewState.ERROR);
                    }
                    break;

                default:
                    super.handleMessage(msg);
                    break;
            }

            mIsLoading = false;
        }
    };

    @Override
    public void onPhotoTap(View view) {
        // NOOP
    }

    @Override
    public void onPhotoLongTapListener(View view) {
        // NOOP
    }

    @Override
    public void onPlayTap(final ProgressBar prog, final ImageButton play, final ImageView image, final VideoView video) {
        // NOOP
    }

    @Override
    public void onLinkTap(View view, @Nullable String url) {
        if (!TextUtils.isEmpty(url)) {
            LinkUtils.LinkMatch match = LinkUtils.findImgurLinkMatch(url);

            switch (match) {
                case GALLERY:
                    Intent intent = ViewActivity.createIntent(getActivity(), url).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    break;

                case IMAGE_URL:
                    PopupImageDialogFragment.getInstance(url, url.endsWith(".gif"), true, false)
                            .show(getFragmentManager(), "popup");
                    break;

                case VIDEO_URL:
                    PopupImageDialogFragment.getInstance(url, true, true, true)
                            .show(getFragmentManager(), "popup");
                    break;

                case IMAGE:
                    String[] split = url.split("\\/");
                    PopupImageDialogFragment.getInstance(split[split.length - 1], false, false, false)
                            .show(getFragmentManager(), "popup");
                    break;

                case NONE:
                default:
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));

                    if (browserIntent.resolveActivity(getActivity().getPackageManager()) != null) {
                        startActivity(browserIntent);
                    } else {
                        SnackBar.show(getActivity(), R.string.cant_launch_intent);
                    }
                    break;
            }
        }
    }

    @Override
    public void onViewRepliesTap(View view) {
        // NOOP
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putInt(KEY_CURRENT_PAGE, mCurrentPage);
        outState.putString(KEY_ENDPOINT, mCurrentEndpoint.getUrl());
        outState.putParcelable(KEY_USERNAME, mSelectedUser);

        if (mAdapter != null && !mAdapter.isEmpty()) {
            outState.putParcelableArrayList(KEY_ITEMS, mAdapter.retainItems());
            outState.putInt(KEY_CURRENT_POSITION, mGridView.getFirstVisiblePosition());
        }

        super.onSaveInstanceState(outState);
    }
}
