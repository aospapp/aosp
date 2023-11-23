package com.android.car.media;

import static android.car.media.CarMediaIntents.EXTRA_MEDIA_COMPONENT;
import static android.car.media.CarMediaManager.MEDIA_SOURCE_MODE_BROWSE;

import android.car.Car;
import android.car.media.CarMediaIntents;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.android.car.media.common.source.MediaSource;
import com.android.car.media.common.source.MediaSourceViewModel;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * A trampoline activity that handles the {@link Car#CAR_INTENT_ACTION_MEDIA_TEMPLATE} implicit
 * intent, and fires up either the Media Center's {@link MediaActivity}, or the specialized
 * application if the selected media source is custom (e.g. the Radio app).
 */
public class MediaDispatcherActivity extends FragmentActivity {

    private static final String TAG = "MediaDispatcherActivity";
    private static Set<String> sCustomMediaComponents = null;

    static boolean isCustomMediaSource(Resources res, @Nullable MediaSource source) {
        if (sCustomMediaComponents == null) {
            sCustomMediaComponents = new HashSet<>();
            sCustomMediaComponents.addAll(
                    Arrays.asList(res.getStringArray(R.array.custom_media_packages)));
        }

        return (source != null)
                && sCustomMediaComponents.contains(
                        source.getBrowseServiceComponentName().flattenToString());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        String action = null;
        String componentName = null;
        if (intent != null) {
            action = intent.getAction();
            componentName = intent.getStringExtra(EXTRA_MEDIA_COMPONENT);
        }

        if (Log.isLoggable(TAG, Log.INFO)) {
            Log.i(TAG, "onCreate action: " + action + " component: " + componentName);
        }

        MediaSource mediaSrc = null;
        if (CarMediaIntents.ACTION_MEDIA_TEMPLATE.equals(action)) {
            if (componentName != null) {
                ComponentName mediaSrcComp = ComponentName.unflattenFromString(componentName);
                if (mediaSrcComp != null) {
                    mediaSrc = MediaSource.create(this, mediaSrcComp);
                }
            }
        }

        // Retrieve the current source if none was set. However, do NOT set it and rely on setting
        // the EXTRA_MEDIA_COMPONENT on the intent launched below. This avoids source notifications
        // as well as extra trips back here, all of which would be useless.
        if (mediaSrc == null) {
            MediaSourceViewModel mediaSrcVM = MediaSourceViewModel.get(getApplication(),
                    MEDIA_SOURCE_MODE_BROWSE);
            mediaSrc = mediaSrcVM.getPrimaryMediaSource().getValue();
        }

        Intent newIntent = null;
        if ((mediaSrc != null) && isCustomMediaSource(getResources(), mediaSrc)) {
            // Launch custom app (e.g. Radio)
            String srcPackage = mediaSrc.getPackageName();
            newIntent = getPackageManager().getLaunchIntentForPackage(srcPackage);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Getting launch intent for package : " + srcPackage + (newIntent != null
                        ? " succeeded" : " failed"));
            }
        }
        if (newIntent == null) {
            // Launch media center
            newIntent = new Intent(this, MediaActivity.class);
        }

        // Add the selected media source to the intent so the launched activity gets it right away
        if (mediaSrc != null) {
            newIntent.putExtra(EXTRA_MEDIA_COMPONENT,
                    mediaSrc.getBrowseServiceComponentName().flattenToString());
        }

        newIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(newIntent);
        finish();
    }
}
