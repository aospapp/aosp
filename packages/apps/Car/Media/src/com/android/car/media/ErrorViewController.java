package com.android.car.media;

import android.app.PendingIntent;
import android.car.content.pm.CarPackageManager;
import android.car.drivingstate.CarUxRestrictions;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.android.car.apps.common.UxrButton;
import com.android.car.apps.common.UxrTextView;
import com.android.car.apps.common.util.ViewUtils;
import com.android.car.media.common.source.MediaSource;

/**
 * A view controller that displays the playback state error iif there is no browse tree.
 */
public class ErrorViewController extends ViewControllerBase {
    private final String TAG = "ErrorViewController";

    // mErrorMessageView is defined explicitly as a UxrTextView instead of a TextView to
    // provide clarity as it may be misleading to assume that mErrorMessageView extends all TextView
    // methods. In addition, it increases discoverability of runtime issues that may occur.
    private final UxrTextView mErrorMessageView;
    private final UxrButton mErrorButton;


    ErrorViewController(FragmentActivity activity,
            CarPackageManager carPackageManager, ViewGroup container) {
        super(activity, carPackageManager, container, R.layout.fragment_error);

        mErrorMessageView = mContent.findViewById(R.id.error_message);
        mErrorButton = mContent.findViewById(R.id.error_button);
    }

    @Override
    void onMediaSourceChanged(@Nullable MediaSource mediaSource) {
        super.onMediaSourceChanged(mediaSource);

        mAppBarView.setListener(new BasicAppBarListener());
        mAppBarView.setTitle(getAppBarDefaultTitle(mediaSource));

        ViewUtils.hideViewAnimated(mErrorMessageView, 0);
        ViewUtils.hideViewAnimated(mErrorButton, 0);
    }

    public void setError(String message, String label, PendingIntent pendingIntent,
            boolean isDistractionOptimized) {
        mErrorMessageView.setText(message);

        // Only show the error button if the error is actionable.
        if (label != null && pendingIntent != null) {
            mErrorButton.setText(label);

            mErrorButton.setUxRestrictions(isDistractionOptimized
                    ? CarUxRestrictions.UX_RESTRICTIONS_BASELINE
                    : CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP);

            mErrorButton.setOnClickListener(v -> {
                try {
                    pendingIntent.send();
                } catch (PendingIntent.CanceledException e) {
                    if (Log.isLoggable(TAG, Log.ERROR)) {
                        Log.e(TAG, "Pending intent canceled");
                    }
                }
            });
            mErrorButton.setVisibility(View.VISIBLE);
        } else {
            mErrorButton.setVisibility(View.GONE);
        }

        ViewUtils.showViewAnimated(mErrorMessageView, mFadeDuration);
        ViewUtils.showViewAnimated(mErrorButton, mFadeDuration);
    }
}
