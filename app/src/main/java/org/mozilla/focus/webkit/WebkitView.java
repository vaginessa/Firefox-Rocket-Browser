/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.webkit;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewDatabase;

import org.mozilla.focus.BuildConfig;
import org.mozilla.focus.history.BrowsingHistoryManager;
import org.mozilla.focus.history.model.Site;
import org.mozilla.focus.utils.AppConstants;
import org.mozilla.focus.utils.FavIconUtils;
import org.mozilla.focus.utils.FileUtils;
import org.mozilla.focus.utils.SupportUtils;
import org.mozilla.focus.utils.ThreadUtils;
import org.mozilla.focus.utils.UrlUtils;
import org.mozilla.focus.web.Download;
import org.mozilla.focus.web.IWebView;
import org.mozilla.focus.web.WebViewProvider;

public class WebkitView extends NestedWebView implements IWebView, SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String KEY_CURRENTURL = "currenturl";

    private IWebView.Callback callback;
    private FocusWebViewClient client;
    private final FocusWebChromeClient webChromeClient;
    private final LinkHandler linkHandler;

    public WebkitView(Context context, AttributeSet attrs) {
        super(context, attrs);

        client = new FocusWebViewClient(getContext().getApplicationContext());

        setWebViewClient(client);
        setWebChromeClient(webChromeClient = new FocusWebChromeClient());
        setDownloadListener(createDownloadListener());

        if (BuildConfig.DEBUG) {
            setWebContentsDebuggingEnabled(true);
        }

        setLongClickable(true);

        linkHandler = new LinkHandler(this);
        setOnLongClickListener(linkHandler);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        PreferenceManager.getDefaultSharedPreferences(getContext()).registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        PreferenceManager.getDefaultSharedPreferences(getContext()).unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        WebViewProvider.applyAppSettings(getContext(), getSettings());
    }

    @Override
    public void restoreWebviewState(Bundle savedInstanceState) {
        // We need to have a different method name because restoreState() returns
        // a WebBackForwardList, and we can't overload with different return types:
        final WebBackForwardList backForwardList = restoreState(savedInstanceState);

        // Pages are only added to the back/forward list when loading finishes. If a new page is
        // loading when the Activity is paused/killed, then that page won't be in the list,
        // and needs to be restored separately to the history list. We detect this by checking
        // whether the last fully loaded page (getCurrentItem()) matches the last page that the
        // WebView was actively loading (which was retrieved during onSaveInstanceState():
        // WebView.getUrl() always returns the currently loading or loaded page).
        // If the app is paused/killed before the initial page finished loading, then the entire
        // list will be null - so we need to additionally check whether the list even exists.

        final String desiredURL = savedInstanceState.getString(KEY_CURRENTURL);

        // If WebView was connecting to a non-exist host (ie. 1.1.1.1:42), getUrl() returns null
        // in onSaveInstanceState. In any cases we can not get desiredURL, no need to load it.
        if (TextUtils.isEmpty(desiredURL)) {
            return;
        }

        client.notifyCurrentURL(desiredURL);

        if (backForwardList != null &&
                backForwardList.getCurrentItem().getUrl().equals(desiredURL)) {
            // restoreState doesn't actually load the current page, it just restores navigation history,
            // so we also need to explicitly reload in this case:
            reload();
        } else {
            loadUrl(desiredURL);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        saveState(outState);
        // See restoreWebViewState() for an explanation of why we need to save this in _addition_
        // to WebView's state
        outState.putString(KEY_CURRENTURL, getUrl());
    }

    @Override
    public void setBlockingEnabled(boolean enabled) {
        client.setBlockingEnabled(enabled);
    }

    public boolean isBlockingEnabled() {
        return client.isBlockingEnabled();
    }

    @Override
    public void setCallback(Callback callback) {
        if (callback != null) {
            callback = new CallbackWrapper(callback);
        }
        this.callback = callback;
        client.setCallback(this.callback);
        linkHandler.setCallback(this.callback);
    }

    public void loadUrl(String url) {
        // We need to check external URL handling here - shouldOverrideUrlLoading() is only
        // called by webview when clicking on a link, and not when opening a new page for the
        // first time using loadUrl().
        if (!client.shouldOverrideUrlLoading(this, url)) {
            super.loadUrl(url);
        }

        client.notifyCurrentURL(url);
    }

    @Override
    public void cleanup() {
        clearFormData();
        clearHistory();
        clearMatches();
        clearSslPreferences();
        clearCache(true);

        // We don't care about the callback - we just want to make sure cookies are gone
        CookieManager.getInstance().removeAllCookies(null);

        WebStorage.getInstance().deleteAllData();

        final WebViewDatabase webViewDatabase = WebViewDatabase.getInstance(getContext());
        // It isn't entirely clear how this differs from WebView.clearFormData()
        webViewDatabase.clearFormData();
        webViewDatabase.clearHttpAuthUsernamePassword();
    }

    public static void deleteContentFromKnownLocations(final Context context) {
        ThreadUtils.postToBackgroundThread(new Runnable() {
            @Override
            public void run() {
                // We call all methods on WebView to delete data. But some traces still remain
                // on disk. This will wipe the whole webview directory.
                FileUtils.deleteWebViewDirectory(context);

                // WebView stores some files in the cache directory. We do not use it ourselves
                // so let's truncate it.
                FileUtils.truncateCacheDirectory(context);
            }
        });
    }

    private class FocusWebChromeClient extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (callback != null) {
                callback.onProgress(newProgress);
            }
        }

        @Override
        public void onReceivedIcon(WebView view, Bitmap icon) {
            final String url = view.getUrl();
            if (TextUtils.isEmpty(url)) {
                return;
            }

            Site site = new Site();
            site.setTitle(view.getTitle());
            site.setUrl(url);
            site.setFavIcon(FavIconUtils.getRefinedBitmap(getResources(), icon, FavIconUtils.getRepresentativeCharacter(url)));
            BrowsingHistoryManager.getInstance().updateLastEntry(site, null);
        }

        @Override
        public void onShowCustomView(View view, final CustomViewCallback webviewCallback) {
            final FullscreenCallback fullscreenCallback = new FullscreenCallback() {
                @Override
                public void fullScreenExited() {
                    webviewCallback.onCustomViewHidden();
                }
            };

            callback.onEnterFullScreen(fullscreenCallback, view);
        }

        @Override
        public void onHideCustomView() {
            callback.onExitFullScreen();
        }


        @Override
        public void onPermissionRequest(PermissionRequest request) {
            super.onPermissionRequest(request);
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin,
                                                       GeolocationPermissions.Callback glpcallback) {
            callback.onGeolocationPermissionsShowPrompt(origin, glpcallback);
        }

        @Override
        public void onGeolocationPermissionsHidePrompt() {
            super.onGeolocationPermissionsHidePrompt();
        }

        @Override
        public boolean onShowFileChooser(WebView webView,
                                         ValueCallback<Uri[]> filePathCallback,
                                         WebChromeClient.FileChooserParams fileChooserParams) {

            return callback.onShowFileChooser(webView, filePathCallback, fileChooserParams);
        }
    }

    public void insertBrowsingHistory() {
        final String url = getUrl();
        if (TextUtils.isEmpty(url)) {
            return;
        } else if (SupportUtils.BLANK_URL.equals(url)) {
            return;
        }

        if (!UrlUtils.isHttpOrHttps(url)) {
            return;
        }

        evaluateJavascript("(function() { return document.getElementById('mozillaErrorPage'); })();",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String errorPage) {
                        if (!"null".equals(errorPage)) {
                            return;
                        }

                        Site site = new Site();
                        site.setUrl(url);
                        site.setTitle(getTitle());
                        site.setLastViewTimestamp(System.currentTimeMillis());
                        site.setFavIcon(FavIconUtils.getInitialBitmap(getResources(), null, FavIconUtils.getRepresentativeCharacter(url)));
                        BrowsingHistoryManager.getInstance().insert(site, null);
                    }
                });
    }

    private static class CallbackWrapper implements IWebView.Callback {
        final IWebView.Callback callback;

        CallbackWrapper(@NonNull IWebView.Callback callback) {
            this.callback = callback;
        }

        @Override
        public void onPageStarted(String url) {
            this.callback.onPageStarted(url);
        }

        @Override
        public void onPageFinished(boolean isSecure) {
            this.callback.onPageFinished(isSecure);
        }

        @Override
        public void onProgress(int progress) {
            this.callback.onProgress(progress);
        }

        @Override
        public void onURLChanged(String url) {
            this.callback.onURLChanged(url);
        }

        @Override
        public boolean handleExternalUrl(String url) {
            return callback.handleExternalUrl(url);
        }

        @Override
        public void onDownloadStart(Download download) {
            this.callback.onDownloadStart(download);
        }

        @Override
        public void onLongPress(HitTarget hitTarget) {
            this.callback.onLongPress(hitTarget);
        }

        @Override
        public void onEnterFullScreen(@NonNull FullscreenCallback callback, @Nullable View view) {
            this.callback.onEnterFullScreen(callback, view);
        }

        @Override
        public void onExitFullScreen() {
            this.callback.onExitFullScreen();
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            this.callback.onGeolocationPermissionsShowPrompt(origin, callback);
        }

        @Override
        public boolean onShowFileChooser(WebView webView,
                                         ValueCallback<Uri[]> filePathCallback,
                                         WebChromeClient.FileChooserParams fileChooserParams) {

            return this.callback.onShowFileChooser(webView, filePathCallback, fileChooserParams);
        }

        @Override
        public void updateFailingUrl(String url, boolean updateFromError) {
            this.callback.updateFailingUrl(url, updateFromError);
        }
    }

    private DownloadListener createDownloadListener() {
        return new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
                if (!AppConstants.supportsDownloadingFiles()) {
                    return;
                }

                if (callback != null) {
                    final Download download = new Download(url, userAgent, contentDisposition, mimetype, contentLength, false);
                    callback.onDownloadStart(download);
                }
            }
        };
    }
}