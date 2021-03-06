/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.web;

import android.os.Parcel;
import android.os.Parcelable;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

public class Download implements Parcelable {

    public static final Parcelable.Creator<Download> CREATOR = new Parcelable.Creator<Download>() {

        @Override
        public Download createFromParcel(Parcel source) {
            return new Download(
                    source.readString(),
                    source.readString(),
                    source.readString(),
                    source.readString(),
                    source.readLong(),
                    source.readByte() != 0);
        }

        @Override
        public Download[] newArray(int size) {
            return new Download[size];
        }
    };

    private final String url;
    private final String contentDisposition;
    private final String mimeType;
    private final long contentLength;
    private final String userAgent;
    private final boolean startFromContextMenu;

    public Download(@NonNull String url,
                    @NonNull String userAgent,
                    @NonNull String contentDisposition,
                    @NonNull String mimeType,
                    long contentLength,
                    boolean startFromContextMenu) {
        this.url = url;
        this.userAgent = userAgent;
        this.contentDisposition = contentDisposition;
        this.mimeType = mimeType;
        this.contentLength = contentLength;
        this.startFromContextMenu = startFromContextMenu;
    }

    public String getUrl() {
        return url;
    }

    public String getContentDisposition() {
        return contentDisposition;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getContentLength() {
        return contentLength;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public boolean isStartFromContextMenu() {
        return startFromContextMenu;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(url);
        dest.writeString(userAgent);
        dest.writeString(contentDisposition);
        dest.writeString(mimeType);
        dest.writeLong(contentLength);
        dest.writeByte((byte) (startFromContextMenu ? 0 : 1));
    }
}
