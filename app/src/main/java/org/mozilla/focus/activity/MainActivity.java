/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.activity;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.BottomSheetDialog;
import android.support.design.widget.Snackbar;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import org.mozilla.focus.R;
import org.mozilla.focus.download.DownloadInfo;
import org.mozilla.focus.download.DownloadInfoManager;
import org.mozilla.focus.fragment.BrowserFragment;
import org.mozilla.focus.fragment.FirstrunFragment;
import org.mozilla.focus.fragment.ListPanelDialog;
import org.mozilla.focus.fragment.ScreenCaptureDialogFragment;
import org.mozilla.focus.home.HomeFragment;
import org.mozilla.focus.locale.LocaleAwareAppCompatActivity;
import org.mozilla.focus.screenshot.ScreenshotCaptureTask;
import org.mozilla.focus.screenshot.ScreenshotGridFragment;
import org.mozilla.focus.screenshot.ScreenshotViewerActivity;
import org.mozilla.focus.urlinput.UrlInputFragment;
import org.mozilla.focus.utils.Constants;
import org.mozilla.focus.utils.DialogUtils;
import org.mozilla.focus.utils.FileUtils;
import org.mozilla.focus.utils.FormatUtils;
import org.mozilla.focus.utils.IntentUtils;
import org.mozilla.focus.utils.NoRemovableStorageException;
import org.mozilla.focus.utils.SafeIntent;
import org.mozilla.focus.utils.Settings;
import org.mozilla.focus.utils.StorageUtils;
import org.mozilla.focus.web.BrowsingSession;
import org.mozilla.focus.web.IWebView;
import org.mozilla.focus.web.WebViewProvider;
import org.mozilla.focus.widget.FragmentListener;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.List;

public class MainActivity extends LocaleAwareAppCompatActivity implements FragmentListener,SharedPreferences.OnSharedPreferenceChangeListener{

    public static final String EXTRA_TEXT_SELECTION = "text_selection";
    private static int REQUEST_CODE_STORAGE_PERMISSION = 101;
    private static final Handler HANDLER = new Handler();

    private String pendingUrl;

    private BottomSheetDialog menu;
    private View nextButton;
    private View loadingButton;
    private View shareButton;
    private View captureButton;
    private View refreshIcon;
    private View stopIcon;

    private MainMediator mediator;
    private boolean safeForFragmentTransactions = false;
    private boolean hasPendingScreenCaptureTask = false;
    private DialogFragment mDialogFragment;

    private BroadcastReceiver uiMessageReceiver;
    private static boolean sIsNewCreated = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        asyncInitialize();

        setContentView(R.layout.activity_main);
        initViews();
        initBroadcastReceivers();

        mediator = new MainMediator(this);

        SafeIntent intent = new SafeIntent(getIntent());

        if (savedInstanceState == null) {
            if (Intent.ACTION_VIEW.equals(intent.getAction())) {
                final String url = intent.getDataString();

                BrowsingSession.getInstance().loadCustomTabConfig(this, intent);

                if (Settings.getInstance(this).shouldShowFirstrun()) {
                    pendingUrl = url;
                    this.mediator.showFirstRun();
                } else {
                    this.mediator.showBrowserScreen(url, true);
                }
            } else {
                if (Settings.getInstance(this).shouldShowFirstrun()) {
                    this.mediator.showFirstRun();
                } else {
                    this.mediator.showHomeScreen();
                }
            }
        }
        WebViewProvider.preload(this);

        if(sIsNewCreated && (!Settings.getInstance(this).didShowRateAppDialog() || !Settings.getInstance(this).didShowShareAppDialog())) {
            sIsNewCreated = false;
            Settings.getInstance(this).increaseAppCreateCounter();
            if(!Settings.getInstance(this).didShowRateAppDialog() && Settings.getInstance(this).getAppCreateCount() >= DialogUtils.APP_CREATE_THRESHOLD_FOR_RATE_APP) {
                DialogUtils.showRateAppDialog(this);
            } else if(!Settings.getInstance(this).didShowShareAppDialog() && Settings.getInstance(this).getAppCreateCount() >= DialogUtils.APP_CREATE_THRESHOLD_FOR_SHARE_APP) {
                DialogUtils.showShareAppDialog(this);
            }
        }

    }

    private void initBroadcastReceivers() {
        uiMessageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                switch (intent.getAction()) {
                    case Constants.ACTION_NOTIFY_UI:
                        final CharSequence msg = intent.getCharSequenceExtra(Constants.EXTRA_MESSAGE);
                        showMessage(msg);
                        break;
                    case Constants.ACTION_NOTIFY_RELOCATE_FINISH:
                        showOpenSnackBar(intent.getLongExtra(Constants.EXTRA_ROW_ID, -1));
                        break;
                    default:
                        break;
                }
            }
        };
    }

    @Override
    public void applyLocale() {
        // re-create bottom sheet menu
        setUpMenu();
    }

    @Override
    protected void onStart() {
        // TODO: handle fragment creation
        //HomeFragment homeFragment = (HomeFragment) getSupportFragmentManager().findFragmentByTag(HomeFragment.FRAGMENT_TAG);
        //if (homeFragment != null) {
        //    getTopSitesPresenter().setView(homeFragment);
        //}
        //UrlInputFragment urlInputFragment = (UrlInputFragment) getSupportFragmentManager().findFragmentByTag(UrlInputFragment.FRAGMENT_TAG);
        //if (urlInputFragment != null) {
        //    getUrlInputPresenter().setView(urlInputFragment);
        //}
        super.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();

        PreferenceManager.getDefaultSharedPreferences(this)
                .registerOnSharedPreferenceChangeListener(this);

        final IntentFilter uiActionFilter = new IntentFilter(Constants.ACTION_NOTIFY_UI);
        uiActionFilter.addCategory(Constants.CATEGORY_FILE_OPERATION);
        uiActionFilter.addAction(Constants.ACTION_NOTIFY_RELOCATE_FINISH);
        LocalBroadcastManager.getInstance(this).registerReceiver(uiMessageReceiver, uiActionFilter);
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        safeForFragmentTransactions = true;
        if(hasPendingScreenCaptureTask) {
            final BrowserFragment browserFragment = getBrowserFragment();
            if (browserFragment != null && browserFragment.isVisible()) {
                showLoadingAndCapture(browserFragment);
            }
            hasPendingScreenCaptureTask = false;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        LocalBroadcastManager.getInstance(this).unregisterReceiver(uiMessageReceiver);

        safeForFragmentTransactions = false;

        PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent unsafeIntent) {
        final SafeIntent intent = new SafeIntent(unsafeIntent);
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            // We can't update our fragment right now because we need to wait until the activity is
            // resumed. So just remember this URL and load it in onResumeFragments().
            pendingUrl = intent.getDataString();
        }

        // We do not care about the previous intent anymore. But let's remember this one.
        setIntent(unsafeIntent);
        BrowsingSession.getInstance().loadCustomTabConfig(this, intent);
    }

    @Override
    protected void onResumeFragments() {
        super.onResumeFragments();

        if (pendingUrl != null && !Settings.getInstance(this).shouldShowFirstrun()) {
            // We have received an URL in onNewIntent(). Let's load it now.
            // Unless we're trying to show the firstrun screen, in which case we leave it pending until
            // firstrun is dismissed.
            this.mediator.showBrowserScreen(pendingUrl, true);
            pendingUrl = null;
        }
    }

    private void initViews() {
        int visibility = getWindow().getDecorView().getSystemUiVisibility();
        // do not overwrite existing value
        visibility |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        getWindow().getDecorView().setSystemUiVisibility(visibility);

        setUpMenu();
    }

    private void setUpMenu() {
        final View sheet = getLayoutInflater().inflate(R.layout.bottom_sheet_main_menu, null);
        menu = new BottomSheetDialog(this, R.style.BottomSheetTheme);
        menu.setContentView(sheet);
        menu.setCanceledOnTouchOutside(true);
        nextButton = menu.findViewById(R.id.action_next);
        loadingButton = menu.findViewById(R.id.action_loading);
        shareButton = menu.findViewById(R.id.action_share);
        captureButton = menu.findViewById(R.id.capture_page);
        refreshIcon = menu.findViewById(R.id.action_refresh);
        stopIcon = menu.findViewById(R.id.action_stop);
        menu.findViewById(R.id.menu_turbomode).setSelected(isTurboEnabled());
        menu.findViewById(R.id.menu_blockimg).setSelected(isBlockingImages());
    }

    private BrowserFragment getVisibleBrowserFragment() {
        final BrowserFragment browserFragment = getBrowserFragment();
        if (browserFragment == null || !browserFragment.isVisible()) {
            return null;
        } else {
            return browserFragment;
        }
    }

    private void showMenu() {
        updateMenu();
        menu.show();
    }

    private void updateMenu() {
        final BrowserFragment browserFragment = getVisibleBrowserFragment();
        final boolean hasLoadedPage = browserFragment != null && !browserFragment.isLoading();
        final boolean canGoForward = browserFragment != null && browserFragment.canGoForward();

        setEnable(nextButton, canGoForward);
        setLoadingButton(browserFragment);
        setEnable(shareButton, browserFragment != null);
        setEnable(captureButton, hasLoadedPage);
    }

    private boolean isTurboEnabled() {
        return Settings.getInstance(this).shouldUseTurboMode();
    }

    private boolean isBlockingImages() {
        return Settings.getInstance(this).shouldBlockImages();
    }

    private Fragment getTopHomeFragment() {
        final Fragment homeFragment = this.mediator.getTopHomeFragmet();
        if (homeFragment == null) {
            return null;
        } else {
            return homeFragment;
        }
    }

    private void showListPanel(int type) {
        DialogFragment dialogFragment = ListPanelDialog.newInstance(type);
        dialogFragment.setCancelable(true);
        final Fragment homeFragment = getTopHomeFragment();
        if (homeFragment != null) {
            dialogFragment.setTargetFragment(homeFragment, HomeFragment.REFRESH_REQUEST_CODE);
        }
        dialogFragment.show(getSupportFragmentManager(), "");
        mDialogFragment = dialogFragment;
    }

    public void onMenuItemClicked(View v) {
        final int stringResource;
        if(!v.isEnabled()) {
            return;
        }
        menu.cancel();
        switch (v.getId()) {
            case R.id.menu_blockimg:
                //  Toggle
                final boolean blockingImages = !isBlockingImages();
                Settings.getInstance(this).setBlockImages(blockingImages);

                v.setSelected(blockingImages);
                stringResource = blockingImages ? R.string.message_enable_block_image : R.string.message_disable_block_image;
                Toast.makeText(this, stringResource, Toast.LENGTH_SHORT).show();

                break;
            case R.id.menu_turbomode:
                //  Toggle
                final boolean turboEnabled = !isTurboEnabled();
                Settings.getInstance(this).setTurboMode(turboEnabled);

                v.setSelected(turboEnabled);
                stringResource = turboEnabled ? R.string.message_enable_turbo_mode : R.string.message_disable_turbo_mode;
                Toast.makeText(this, stringResource, Toast.LENGTH_SHORT).show();

                break;
            case R.id.menu_delete:
                onDeleteClicked();
                break;
            case R.id.menu_download:
                onDownloadClicked();
                break;
            case R.id.menu_history:
                onHistoryClicked();
                break;
            case R.id.menu_screenshots:
                onScreenshotsClicked();
                break;
            case R.id.menu_preferences:
                onPreferenceClicked();
                break;
            case R.id.action_next:
            case R.id.action_loading:
            case R.id.action_share:
            case R.id.capture_page:
                onMenuBrowsingItemClicked(v);
                break;
            default:
                throw new RuntimeException("Unknown id in menu, onMenuItemClicked() is only for" +
                        " known ids");
        }
    }

    private void setEnable(View v, boolean enable) {
        v.setEnabled(enable);
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for(int i=0 ; i < vg.getChildCount() ; i++) {
                setEnable(((ViewGroup) v).getChildAt(i), enable);
            }
        }
    }

    private void setLoadingButton(BrowserFragment fragment) {
        if (fragment == null) {
            setEnable(loadingButton, false);
            refreshIcon.setVisibility(View.VISIBLE);
            stopIcon.setVisibility(View.GONE);
            loadingButton.setTag(false);
        } else {
            setEnable(loadingButton, true);
            boolean isLoading = fragment.isLoading();
            refreshIcon.setVisibility(isLoading ? View.GONE : View.VISIBLE);
            stopIcon.setVisibility(isLoading ? View.VISIBLE : View.GONE);
            loadingButton.setTag(isLoading);
        }
    }

    public void onMenuBrowsingItemClicked(View v) {
        final BrowserFragment browserFragment = getVisibleBrowserFragment();
        if (browserFragment == null) {
            return;
        }
        switch (v.getId()) {
            case R.id.action_next:
                onNextClicked(browserFragment);
                break;
            case R.id.action_loading:
                if ((boolean) v.getTag()) {
                    onStopClicked(browserFragment);
                } else {
                    onRefreshClicked(browserFragment);
                }
                break;
            case R.id.action_share:
                onShraeClicked(browserFragment);
                break;
            case R.id.capture_page:
                onCapturePageClicked(browserFragment);
                break;
            default:
                throw new RuntimeException("Unknown id in menu, onMenuBrowsingItemClicked() is" +
                        " only for known ids");
        }
    }

    private void onPreferenceClicked() {
        openPreferences();
    }

    private void onDownloadClicked() {
        showListPanel(ListPanelDialog.TYPE_DOWNLOADS);
    }

    private void onHistoryClicked() {
        showListPanel(ListPanelDialog.TYPE_HISTORY);
    }

    private void onScreenshotsClicked() {
        showListPanel(ListPanelDialog.TYPE_SCREENSHOTS);
    }

    private void onDeleteClicked() {
        final long diff = FileUtils.clearCache(this);
        final int stringId = (diff < 0) ? R.string.message_clear_cache_fail : R.string.message_cleared_cached;
        final String msg = getString(stringId, FormatUtils.getReadableStringFromFileSize(diff));
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private BrowserFragment getBrowserFragment() {
        return (BrowserFragment) getSupportFragmentManager().findFragmentByTag(BrowserFragment.FRAGMENT_TAG);
    }

    private void onBackClicked(final BrowserFragment browserFragment) {
        browserFragment.goBack();
    }

    private void onNextClicked(final BrowserFragment browserFragment) {
        browserFragment.goForward();
    }

    private void onRefreshClicked(final BrowserFragment browserFragment) {
        browserFragment.reload();
    }

    private void onStopClicked(final BrowserFragment browserFragment) {
        browserFragment.stop();
    }

    private void onCapturePageClicked(final BrowserFragment browserFragment) {
        if (PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            // We do have the permission to write to the external storage.
            showLoadingAndCapture(browserFragment);
        } else {
            // We do not have the permission to write to the external storage. Request the permission and start the
            // capture from onRequestPermissionsResult().
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CODE_STORAGE_PERMISSION);
            }
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        // Only refresh when disabling turbo mode
        if (this.getResources().getString(R.string.pref_key_turbo_mode).equals(key)){
            final boolean turboEnabled = isTurboEnabled();
            BrowserFragment browserFragment = getVisibleBrowserFragment();
            if (browserFragment != null) {
                browserFragment.setBlockingEnabled(turboEnabled);
                // Reload if we're closing Turbo mode since we should be fixing something
                if(!turboEnabled) {
                    browserFragment.reload();
                }
            }
            menu.findViewById(R.id.menu_turbomode).setSelected(turboEnabled);
        } else if (this.getResources().getString(R.string.pref_key_performance_block_images).equals(key)) {
            final boolean blockingImages = isBlockingImages();
            if (getVisibleBrowserFragment() != null){
                getVisibleBrowserFragment().reload();
            }
            menu.findViewById(R.id.menu_blockimg).setSelected(blockingImages);
        }
        // For turbo mode, a automatic refresh is done when we disable block image.
    }

    private static final class CaptureRunnable extends ScreenshotCaptureTask implements Runnable, BrowserFragment.ScreenshotCallback {

        final WeakReference<Context> refContext;
        final WeakReference<BrowserFragment> refBrowserFragment;
        final WeakReference<ScreenCaptureDialogFragment> refScreenCaptureDialogFragment;
        final WeakReference<View> refContainerView;

        CaptureRunnable(Context context, BrowserFragment browserFragment, ScreenCaptureDialogFragment screenCaptureDialogFragment, View container) {
            super(context);
            refContext = new WeakReference<>(context);
            refBrowserFragment = new WeakReference<>(browserFragment);
            refScreenCaptureDialogFragment = new WeakReference<>(screenCaptureDialogFragment);
            refContainerView = new WeakReference<>(container);
        }

        @Override
        public void run() {
            BrowserFragment browserFragment = refBrowserFragment.get();
            if (browserFragment == null) {
                return;
            }
            if(browserFragment.capturePage(this)){
                //  onCaptureComplete called
            } else {
                //  Capture failed
                ScreenCaptureDialogFragment screenCaptureDialogFragment = refScreenCaptureDialogFragment.get();
                if (screenCaptureDialogFragment != null) {
                    screenCaptureDialogFragment.dismiss();
                }
                promptScreenshotResult(R.string.screenshot_failed);
            }
        }

        @Override
        public void onCaptureComplete(String title, String url, Bitmap bitmap) {
            Context context = refContext.get();
            if (context == null) {
                return;
            }

            execute(title, url, bitmap);
        }

        @Override
        protected void onPostExecute(final String path) {
            ScreenCaptureDialogFragment screenCaptureDialogFragment = refScreenCaptureDialogFragment.get();
            if (screenCaptureDialogFragment == null) {
                cancel(true);
                return;
            }
            final int captureResultResource = TextUtils.isEmpty(path) ? R.string.screenshot_failed : R.string.screenshot_saved;
            screenCaptureDialogFragment.getDialog().setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    promptScreenshotResult(captureResultResource);
                }
            });
            if (TextUtils.isEmpty(path)) {
                screenCaptureDialogFragment.dismiss();
            } else {
                screenCaptureDialogFragment.dismiss(true);
            }
        }

        private void promptScreenshotResult(int snackbarTitleId){
            Context context = refContext.get();
            if(context == null){
                return;
            }
            Toast.makeText(context, snackbarTitleId, Toast.LENGTH_SHORT).show();
        }

    }

    private void showLoadingAndCapture(final BrowserFragment browserFragment) {
        if (!safeForFragmentTransactions) {
            return;
        }
        hasPendingScreenCaptureTask = false;
        final ScreenCaptureDialogFragment capturingFragment = ScreenCaptureDialogFragment.newInstance();
        capturingFragment.show(getSupportFragmentManager(), "capturingFragment");

        final int WAIT_INTERVAL = 150;
        // Post delay to wait for Dialog to show
        HANDLER.postDelayed(new CaptureRunnable(MainActivity.this, browserFragment, capturingFragment, findViewById(R.id.container)), WAIT_INTERVAL);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                final BrowserFragment browserFragment = getBrowserFragment();
                if (browserFragment == null || !browserFragment.isVisible()) {
                    return;
                }
                hasPendingScreenCaptureTask = true;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if(requestCode == ScreenshotViewerActivity.REQ_CODE_VIEW_SCREENSHOT) {
            if(resultCode == ScreenshotViewerActivity.RESULT_NOTIFY_SCREENSHOT_IS_DELETED) {
                Toast.makeText(this, R.string.message_deleted_screenshot, Toast.LENGTH_SHORT).show();
                if(mDialogFragment != null) {
                    Fragment fragment = mDialogFragment.getChildFragmentManager().findFragmentById(R.id.main_content);
                    if (fragment instanceof ScreenshotGridFragment && data != null) {
                        long id = data.getLongExtra(ScreenshotViewerActivity.EXTRA_SCREENSHOT_ITEM_ID, -1);
                        ((ScreenshotGridFragment) fragment).notifyItemDelete(id);
                    }
                }
            } else if(resultCode == ScreenshotViewerActivity.RESULT_OPEN_URL) {
                if(data != null) {
                    String url = data.getStringExtra(ScreenshotViewerActivity.EXTRA_URL);
                    if(mDialogFragment != null) {
                        mDialogFragment.dismiss();
                    }
                    onNotified(null, TYPE.OPEN_URL, url);
                }
            }
        }
    }

    private void onShraeClicked(final BrowserFragment browserFragment) {
        final Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, browserFragment.getUrl());
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_dialog_title)));
    }

    @Override
    public View onCreateView(String name, Context context, AttributeSet attrs) {
        if (name.equals(IWebView.class.getName())) {
            View v = WebViewProvider.create(this, attrs);
            return v;
        }

        return super.onCreateView(name, context, attrs);
    }

    @Override
    public void onBackPressed() {
        if (!safeForFragmentTransactions) {
            return;
        }
        if (this.mediator.handleBackKey()) {
            return;
        }
        super.onBackPressed();
    }

    public void firstrunFinished() {
        if (pendingUrl != null) {
            // We have received an URL in onNewIntent(). Let's load it now.
            this.mediator.showBrowserScreen(pendingUrl, true);
            pendingUrl = null;
        } else {
            this.mediator.showHomeScreen();
        }
    }

    @Override
    public void onNotified(@NonNull Fragment from, @NonNull TYPE type, @Nullable Object payload) {
        switch (type) {
            case OPEN_URL:
                if ((payload != null) && (payload instanceof String)) {
                    this.mediator.showBrowserScreen(payload.toString(), false);
                }
                break;
            case OPEN_PREFERENCE:
                openPreferences();
                break;
            case SHOW_HOME:
                this.mediator.showHomeScreen();
                break;
            case SHOW_MENU:
                this.showMenu();
                break;
            case UPDATE_MENU:
                this.updateMenu();
                break;
            case SHOW_URL_INPUT:
                if (!safeForFragmentTransactions) {
                    return;
                }
                final String url = (payload != null) ? payload.toString() : null;
                this.mediator.showUrlInput(url);
                break;
            case DISMISS_URL_INPUT:
                this.mediator.dismissUrlInput();
                break;
            case FRAGMENT_STARTED:
                if ((payload != null) && (payload instanceof String)) {
                    this.mediator.onFragmentStarted(((String) payload).toLowerCase());
                }
                break;
            case FRAGMENT_STOPPED:
                if ((payload != null) && (payload instanceof String)) {
                    this.mediator.onFragmentStopped(((String) payload).toLowerCase());
                }
                break;
        }
    }

    public FirstrunFragment createFirstRunFragment() {
        return FirstrunFragment.create();
    }

    public BrowserFragment createBrowserFragment(@NonNull String url) {
        BrowserFragment fragment = BrowserFragment.create(url);
        return fragment;
    }

    public UrlInputFragment createUrlInputFragment(@Nullable String url) {
        final UrlInputFragment fragment = UrlInputFragment.create(url);
        return fragment;
    }

    public HomeFragment createHomeFragment() {
        final HomeFragment fragment = HomeFragment.create();
        return fragment;
    }

    public void sendBrowsingTelemetry() {
        final SafeIntent intent = new SafeIntent(getIntent());
        if (intent.getBooleanExtra(EXTRA_TEXT_SELECTION, false)) {
        } else {
        }
    }

    private void showMessage(@NonNull CharSequence msg) {
        if (TextUtils.isEmpty(msg)) {
            return;
        }

        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void asyncInitialize() {
        (new Thread(new Runnable() {
            @Override
            public void run() {
                asyncCheckStorage();
            }
        })).start();
    }

    /**
     * To check existence of removable storage, and write result to preference
     */
    private void asyncCheckStorage() {
        boolean exist;
        try {
            final File dir = StorageUtils.getTargetDirOnRemovableStorageForDownloads(this, "*/*");
            exist = (dir != null);
        } catch (NoRemovableStorageException e) {
            exist = false;
        }

        Settings.getInstance(this).setRemovableStorageStateOnCreate(exist);
    }

    private void showOpenSnackBar(Long rowId) {
        DownloadInfoManager.getInstance().queryByRowId(rowId, new DownloadInfoManager.AsyncQueryListener() {
            @Override
            public void onQueryComplete(List downloadInfoList) {
                if (downloadInfoList.size() > 0) {
                    final DownloadInfo downloadInfo = (DownloadInfo) downloadInfoList.get(0);

                    final View container = findViewById(R.id.container);
                    String completedStr = getString(R.string.download_completed, downloadInfo.getFileName());
                    Snackbar.make(container, completedStr, Snackbar.LENGTH_LONG)
                            .setAction(R.string.open, new View.OnClickListener() {
                                @Override
                                public void onClick(View view) {
                                    if (TextUtils.isEmpty(downloadInfo.getMediaUri())) {
                                        MediaScannerConnection.scanFile(MainActivity.this,
                                                new String[]{Uri.parse(downloadInfo.getFileUri()).getPath()},
                                                new String[]{downloadInfo.getMimeType()},
                                                new MediaScannerConnection.OnScanCompletedListener() {

                                                    @Override
                                                    public void onScanCompleted(String path, Uri uri) {
                                                        IntentUtils.intentOpenFile(MainActivity.this, uri.toString(), downloadInfo.getMimeType());
                                                    }
                                                });
                                    } else {
                                        IntentUtils.intentOpenFile(view.getContext(), downloadInfo.getMediaUri(), downloadInfo.getMimeType());
                                    }
                                }
                            })
                            .show();
                }
            }
        });
    }
}
