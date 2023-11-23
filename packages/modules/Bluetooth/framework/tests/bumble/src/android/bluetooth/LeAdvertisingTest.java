package android.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.Utils;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.util.Log;

import androidx.core.util.Pair;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import static com.google.common.truth.Truth.assertThat;
import com.google.protobuf.Empty;

import io.grpc.Context.CancellableContext;
import io.grpc.Deadline;
import io.grpc.ManagedChannel;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import pandora.HostGrpc;
import pandora.HostProto.ScanRequest;
import pandora.HostProto.ScanningResponse;


/**
 * Test cases for {@link AdvertiseManager}.
 */
@RunWith(AndroidJUnit4.class)
public class LeAdvertisingTest {

    private static final String LOG_TAG = "LeAdvertisingTest";

    private static final int TIMEOUT_ADVERTISING_MS = 1000;

    private static ManagedChannel mChannel;

    private static HostGrpc.HostBlockingStub mHostBlockingStub;

    private static HostGrpc.HostStub mHostStub;

    @BeforeClass
    public static void setUpClass() throws Exception {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity();
    }

    @Before
    public void setUp() throws Exception {
        // FactorReset is killing the server and restart
        // all channel created before the server restarted
        // cannot be reused
        ManagedChannel channel = OkHttpChannelBuilder
              .forAddress("localhost", 7999)
              .usePlaintext()
              .build();

        HostGrpc.HostBlockingStub stub = HostGrpc.newBlockingStub(channel);
        stub.factoryReset(Empty.getDefaultInstance());

        // terminate the channel
        channel.shutdown().awaitTermination(1, TimeUnit.SECONDS);

        // Create a new channel for all successive grpc calls
        mChannel = OkHttpChannelBuilder
              .forAddress("localhost", 7999)
              .usePlaintext()
              .build();

        mHostBlockingStub = HostGrpc.newBlockingStub(mChannel);
        mHostStub = HostGrpc.newStub(mChannel);
        mHostBlockingStub.withWaitForReady().readLocalAddress(Empty.getDefaultInstance());
    }

    @After
    public void tearDown() throws Exception {
        // terminate the channel
        mChannel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
    }

    @Test
    public void advertisingSet() throws Exception {
        ScanningResponse response = startAdvertising()
                                      .thenCompose(advAddressPair -> scanWithBumble(advAddressPair))
                                      .join();

        Log.i(LOG_TAG, "scan response: " + response);
        assertThat(response).isNotNull();
    }

    private CompletableFuture<Pair<String, Integer>> startAdvertising() {
        CompletableFuture<Pair<String, Integer>> future =
            new CompletableFuture<Pair<String, Integer>>();

        android.content.Context context = ApplicationProvider.getApplicationContext();
        BluetoothManager bluetoothManager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();

        // Start advertising
        BluetoothLeAdvertiser leAdvertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
        AdvertisingSetParameters parameters = new AdvertisingSetParameters.Builder().
             setOwnAddressType(AdvertisingSetParameters.ADDRESS_TYPE_RANDOM).build();
        AdvertiseData advertiseData = new AdvertiseData.Builder().build();
        AdvertiseData scanResponse = new AdvertiseData.Builder().build();
        AdvertisingSetCallback advertisingSetCallback = new AdvertisingSetCallback() {
            @Override
            public void onAdvertisingSetStarted(AdvertisingSet advertisingSet, int txPower,
                    int status) {
                Log.i(LOG_TAG, "onAdvertisingSetStarted " + " txPower:" + txPower
                    + " status:" + status);
                advertisingSet.enableAdvertising(true, TIMEOUT_ADVERTISING_MS, 0);
            }
            @Override
            public void onOwnAddressRead(AdvertisingSet advertisingSet, int addressType,
                    String address) {
                Log.i(LOG_TAG, "onOwnAddressRead " + " addressType:" + addressType
                    + " address:" + address);
                future.complete(new Pair<String, Integer>(address, addressType));
            }
            @Override
            public void onAdvertisingEnabled(AdvertisingSet advertisingSet, boolean enabled,
                    int status) {
                Log.i(LOG_TAG, "onAdvertisingEnabled " + " enabled:" + enabled
                        + " status:" + status);
                advertisingSet.getOwnAddress();
            }
        };
        leAdvertiser.startAdvertisingSet(parameters, advertiseData, scanResponse,
          null, null, 0, 0, advertisingSetCallback);

        return future;
    }

    private CompletableFuture<ScanningResponse> scanWithBumble(Pair<String, Integer> addressPair) {
        final CompletableFuture<ScanningResponse> future =
            new CompletableFuture<ScanningResponse>();
        CancellableContext withCancellation = io.grpc.Context.current().withCancellation();

        String address = addressPair.first;
        int addressType = addressPair.second;

        ScanRequest request = ScanRequest.newBuilder().build();
        StreamObserver<ScanningResponse> responseObserver = new StreamObserver<ScanningResponse>(){
            public void onNext(ScanningResponse response) {
                String addr = "";
                if (addressType == AdvertisingSetParameters.ADDRESS_TYPE_PUBLIC) {
                    addr = Utils.addressStringFromByteString(response.getPublic());
                }
                else {
                    addr = Utils.addressStringFromByteString(response.getRandom());
                }
                Log.i(LOG_TAG,"scan observer: scan response address: " + addr);

                if (addr.equals(address)) {
                    future.complete(response);
                }
            }

            @Override
            public void onError(Throwable e) {
                Log.e(LOG_TAG,"scan observer: on error " + e);
                future.completeExceptionally(e);
            }

            @Override
            public void onCompleted() {
                Log.i(LOG_TAG,"scan observer: on completed");
                future.complete(null);
            }
        };

        Deadline initialDeadline = Deadline.after(TIMEOUT_ADVERTISING_MS, TimeUnit.MILLISECONDS);
        withCancellation.run(() -> mHostStub.withDeadline(initialDeadline)
            .scan(request, responseObserver));

        return future.whenComplete((input, exception) -> {
            withCancellation.cancel(null);
        });
    }
}
