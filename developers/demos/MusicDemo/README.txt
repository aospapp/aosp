Android Automobile sample
=========================


Integration points
------------------

MusicService.java is the main entry point to the integration. It needs to:

    - extend android.service.media.MediaBrowserService, implementing the media browsing related methods onGetRoot and onLoadChildren;
    - start a new MediaSession and notify it's parent of the session's token (super.setSessionToken());
    - set a callback on the MediaSession. The callback will receive all the user's actions, like play, pause, etc;
    - handle all the actual music playing using any method your app prefers (for example, the Android MediaPlayer class)
    - update info about the playing item and the playing queue using MediaSession (setMetadata, setPlaybackState, setQueue, setQueueTitle, etc)
    - handle AudioManager focus change events and react appropriately (eg, pause when audio focus is lost)
    - declare a meta-data tag in AndroidManifest.xml linking to a xml resource
      with a <automotiveApp> root element. For a media app, this must include
      an <uses name="media"/> element as a child.
      For example, in AndroidManifest.xml:
         <meta-data android:name="com.google.android.gms.car.application"
           android:resource="@xml/automotive_app_desc"/>
      And in res/values/automotive_app_desc.xml:
          <?xml version="1.0" encoding="utf-8"?>
          <automotiveApp>
              <uses name="media"/>
          </automotiveApp>

    - be declared in AndroidManifest as an intent receiver for the action android.media.browse.MediaBrowserService:

        <!-- Implement a service  -->
        <service
            android:name=".service.MusicService"
            android:exported="true"
            >
            <intent-filter>
                <action android:name="android.media.browse.MediaBrowserService" />
            </intent-filter>
        </service>


Optionally, you can listen to special intents that notify your app when a car is connected/disconnected. This may be useful if your app has special requirements when running on a car - for example, different media or ads. See CarPlugReceiver for more information.


Customization
-------------

The car media app has only a few customization opportunities. You may:

- Set the background color by using Android L primary color:
    <style name="AppTheme" parent="android:Theme.Material">
        <item name="android:colorPrimary">#ff0000</item>
    </style>

- Add custom actions in the state passed to setPlaybackState(state)

- Handle custom actions in the MediaSession.Callback.onCustomAction



Known issues:
-------------

- Sample: Resuming after pause makes the "Skip to previous" button disappear

- Sample: playFromSearch creates a queue with search results, but then skip to next/previous don't work correctly because the queue is recreated without the search criteria

- Emulator: running menu->search twice throws an exception.

- Emulator: Under some circumstances, stop or onDestroy may never get called on MusicService and the MediaPlayer keeps locking some resources. Then, mediaPlayer.setDataSource on a new MediaPlayer object halts (probably) due to a deadlock. The workaround is to reboot the device.

