package org.robolectric.android.plugins;

import static android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE;

import com.google.auto.service.AutoService;

import org.robolectric.internal.dependency.DependencyResolver;
import org.robolectric.pluginapi.Sdk;
import org.robolectric.pluginapi.SdkProvider;
import org.robolectric.plugins.DefaultSdkProvider;
import org.robolectric.versioning.AndroidVersionInitTools;
import org.robolectric.versioning.AndroidVersions;

import java.io.File;
import java.io.IOException;
import java.util.TreeMap;
import java.util.jar.JarFile;

import javax.annotation.Priority;

@AutoService(SdkProvider.class)
@Priority(Integer.MAX_VALUE)
public class AndroidLocalSdkProvider extends DefaultSdkProvider {

    public AndroidLocalSdkProvider(DependencyResolver dependencyResolver) {
        super(dependencyResolver);
    }

    protected void populateSdks(TreeMap<Integer, Sdk> knownSdks) {
        super.populateSdks(knownSdks);
        System.out.println("In android populate sdk.");
        DefaultSdk localBuiltSdk = new DefaultSdk(10000, "current", "r0", "UpsideDownCake", 9);
        File location = localBuiltSdk.getJarPath().toFile();
        AndroidVersions.AndroidRelease release = null;
        try {
            JarFile jarFile = new JarFile(location);
            release = AndroidVersionInitTools.computeReleaseVersion(jarFile);
            System.out.println("Found release :" + release.toString());
            if (release != null) {
                DefaultSdk currentSdk = new DefaultSdk(release.getSdkInt(), "current", "r0", release.getShortCode(), 17);
                for (int sdkInt : knownSdks.keySet()) {
                    if (sdkInt >= release.getSdkInt()) {
                        System.out.println("Removing :" + sdkInt);
                        knownSdks.remove(sdkInt);
                    }
                }
                knownSdks.put(release.getSdkInt(), currentSdk);
            } else {
                throw new RuntimeException("Could not read the version of the current android-all sdk"
                        + "this prevents robolectric from determining which shadows to apply.");
            }
        } catch (IOException ioe) {
            throw new RuntimeException("Could not read the version of the current android-all sdk"
            + "this prevents robolectric from determining which shadows to apply.", ioe);
        }
    }
}
