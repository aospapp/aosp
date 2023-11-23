package android.nfc.cts;

import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.nfc.INfcCardEmulation;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.*;
import android.os.RemoteException;

import androidx.test.InstrumentationRegistry;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.internal.util.reflection.FieldReader;
import org.mockito.internal.util.reflection.FieldSetter;

import java.util.ArrayList;
import java.util.List;

@RunWith(JUnit4.class)
public class CardEmulationTest {
    private NfcAdapter mAdapter;
    private static final ComponentName mService =
        new ComponentName("android.nfc.cts", "android.nfc.cts.CtsMyHostApduService");

    private INfcCardEmulation mOldService;
    @Mock private INfcCardEmulation mEmulation;

    private boolean supportsHardware() {
        final PackageManager pm = InstrumentationRegistry.getContext().getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION);
    }

    private boolean supportsHardwareForEse() {
        final PackageManager pm = InstrumentationRegistry.getContext().getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_NFC_OFF_HOST_CARD_EMULATION_ESE);
    }

    @Before
    public void setUp() throws NoSuchFieldException, RemoteException {
        MockitoAnnotations.initMocks(this);
        assumeTrue(supportsHardware());
        Context mContext = InstrumentationRegistry.getContext();
        mAdapter = NfcAdapter.getDefaultAdapter(mContext);
        Assert.assertNotNull(mAdapter);

        CardEmulation instance = CardEmulation.getInstance(mAdapter);
        FieldReader serviceField = new FieldReader(instance,
                instance.getClass().getDeclaredField("sService"));
        mOldService = (INfcCardEmulation) serviceField.read();
    }

    @After
    public void tearDown() throws Exception {
        if (!supportsHardware()) return;
        CardEmulation instance = CardEmulation.getInstance(mAdapter);
        FieldSetter.setField(instance,
                instance.getClass().getDeclaredField("sService"), mOldService);
    }

    @Test
    public void getNonNullInstance() {
        CardEmulation instance = CardEmulation.getInstance(mAdapter);
        Assert.assertNotNull(instance);
    }

    @Test
    public void testIsDefaultServiceForCategory() throws NoSuchFieldException, RemoteException {
        CardEmulation instance = createMockedInstance();
        when(mEmulation.isDefaultServiceForCategory(anyInt(), any(ComponentName.class),
            anyString())).thenReturn(true);
        boolean result = instance.isDefaultServiceForCategory(mService,
            CardEmulation.CATEGORY_PAYMENT);
        Assert.assertTrue(result);
    }

    @Test
    public void testIsDefaultServiceForAid() throws NoSuchFieldException, RemoteException {
        CardEmulation instance = createMockedInstance();
        String aid = "00000000000000";
        when(mEmulation.isDefaultServiceForAid(anyInt(), any(ComponentName.class), anyString()))
            .thenReturn(true);
        boolean result = instance.isDefaultServiceForAid(mService, aid);
        Assert.assertTrue(result);
    }

    @Test
    public void testCategoryAllowsForegroundPreferenceWithCategoryPayment() {
        CardEmulation instance = CardEmulation.getInstance(mAdapter);
        boolean result
            = instance.categoryAllowsForegroundPreference(CardEmulation.CATEGORY_PAYMENT);
        Assert.assertTrue(result);
    }

    @Test
    public void testCategoryAllowsForegroundPrefenceWithCategoryOther() {
        CardEmulation instance = CardEmulation.getInstance(mAdapter);
        boolean result
            = instance.categoryAllowsForegroundPreference(CardEmulation.CATEGORY_OTHER);
        Assert.assertTrue(result);
    }

    @Test
    public void testGetSelectionModeForCategoryWithCategoryPaymentAndPaymentRegistered()
        throws NoSuchFieldException, RemoteException {
        CardEmulation instance = createMockedInstance();
        when(mEmulation.isDefaultPaymentRegistered()).thenReturn(true);
        int result = instance.getSelectionModeForCategory(CardEmulation.CATEGORY_PAYMENT);
        Assert.assertEquals(CardEmulation.SELECTION_MODE_PREFER_DEFAULT, result);
    }

    @Test
    public void testGetSelectionModeForCategoryWithCategoryPaymentAndWithoutPaymentRegistered()
        throws NoSuchFieldException, RemoteException {
        CardEmulation instance = createMockedInstance();
        when(mEmulation.isDefaultPaymentRegistered()).thenReturn(false);
        int result = instance.getSelectionModeForCategory(CardEmulation.CATEGORY_PAYMENT);
        Assert.assertEquals(CardEmulation.SELECTION_MODE_ALWAYS_ASK, result);
    }

    @Test
    public void testGetSelectionModeForCategoryWithCategoryOther() {
        CardEmulation instance = CardEmulation.getInstance(mAdapter);
        int result = instance.getSelectionModeForCategory(CardEmulation.CATEGORY_OTHER);
        Assert.assertEquals(CardEmulation.SELECTION_MODE_ASK_IF_CONFLICT, result);
    }

    @Test
    public void testRegisterAidsForService() throws NoSuchFieldException, RemoteException {
        CardEmulation instance = createMockedInstance();
        ArrayList<String> aids = new ArrayList<String>();
        aids.add("00000000000000");
        when(mEmulation.registerAidGroupForService(anyInt(), any(ComponentName.class),
            any(AidGroup.class))).thenReturn(true);
        boolean result
            = instance.registerAidsForService(mService, CardEmulation.CATEGORY_PAYMENT, aids);
        Assert.assertTrue(result);
    }

    @Test
    public void testUnsetOffHostForService() throws NoSuchFieldException, RemoteException {
        assumeTrue(supportsHardwareForEse());
        CardEmulation instance = createMockedInstance();
        when(mEmulation.unsetOffHostForService(anyInt(), any(ComponentName.class)))
            .thenReturn(true);
        boolean result = instance.unsetOffHostForService(mService);
        Assert.assertTrue(result);
    }

    @Test
    public void testSetOffHostForService() throws NoSuchFieldException, RemoteException {
        assumeTrue(supportsHardwareForEse());
        CardEmulation instance = createMockedInstance();
        String offHostSecureElement = "eSE";
        when(mEmulation.setOffHostForService(anyInt(), any(ComponentName.class), anyString()))
            .thenReturn(true);
        boolean result = instance.setOffHostForService(mService, offHostSecureElement);
        Assert.assertTrue(result);
    }

    @Test
    public void testGetAidsForService() throws NoSuchFieldException, RemoteException {
        CardEmulation instance = createMockedInstance();
        ArrayList<String> aids = new ArrayList<String>();
        aids.add("00000000000000");
        AidGroup aidGroup = new AidGroup(aids, CardEmulation.CATEGORY_PAYMENT);
        when(mEmulation.getAidGroupForService(anyInt(), any(ComponentName.class), anyString()))
            .thenReturn(aidGroup);
        List<String> result = instance.getAidsForService(mService, CardEmulation.CATEGORY_PAYMENT);
        Assert.assertEquals(aids, result);
    }

    @Test
    public void testRemoveAidsForService() throws NoSuchFieldException, RemoteException {
        CardEmulation instance = createMockedInstance();
        when(mEmulation.removeAidGroupForService(anyInt(), any(ComponentName.class), anyString()))
            .thenReturn(true);
        boolean result = instance.removeAidsForService(mService, CardEmulation.CATEGORY_PAYMENT);
        Assert.assertTrue(result);
    }

    @Test
    public void testSetPreferredService() throws NoSuchFieldException, RemoteException {
        CardEmulation instance = createMockedInstance();
        Activity activity = createAndResumeActivity();
        when(mEmulation.setPreferredService(any(ComponentName.class))).thenReturn(true);
        boolean result = instance.setPreferredService(activity, mService);
        Assert.assertTrue(result);
    }

    @Test
    public void testUnsetPreferredService() throws NoSuchFieldException, RemoteException {
        CardEmulation instance = createMockedInstance();
        Activity activity = createAndResumeActivity();
        when(mEmulation.unsetPreferredService()).thenReturn(true);
        boolean result = instance.unsetPreferredService(activity);
        Assert.assertTrue(result);
    }

    @Test
    public void testSupportsAidPrefixRegistration() throws NoSuchFieldException, RemoteException {
        CardEmulation instance = createMockedInstance();
        when(mEmulation.supportsAidPrefixRegistration()).thenReturn(true);
        boolean result = instance.supportsAidPrefixRegistration();
        Assert.assertTrue(result);
    }

    @Test
    public void testGetAidsForPreferredPaymentService() throws NoSuchFieldException,
        RemoteException {
        CardEmulation instance = createMockedInstance();
        ArrayList<AidGroup> dynamicAidGroups = new ArrayList<AidGroup>();
        ArrayList<String> aids = new ArrayList<String>();
        aids.add("00000000000000");
        AidGroup aidGroup = new AidGroup(aids, CardEmulation.CATEGORY_PAYMENT);
        dynamicAidGroups.add(aidGroup);
        ApduServiceInfo serviceInfo = new ApduServiceInfo(new ResolveInfo(), false, "",
            new ArrayList<AidGroup>(), dynamicAidGroups, false, 0, 0, "", "", "");
        when(mEmulation.getPreferredPaymentService(anyInt())).thenReturn(serviceInfo);
        List<String> result = instance.getAidsForPreferredPaymentService();
        Assert.assertEquals(aids, result);
    }

    @Test
    public void testGetRouteDestinationForPreferredPaymentServiceWithOnHost()
        throws NoSuchFieldException, RemoteException {
        CardEmulation instance = createMockedInstance();
        ApduServiceInfo serviceInfo = new ApduServiceInfo(new ResolveInfo(), /* onHost = */ true,
            "", new ArrayList<AidGroup>(), new ArrayList<AidGroup>(), false, 0, 0, "", "", "");
        when(mEmulation.getPreferredPaymentService(anyInt())).thenReturn(serviceInfo);
        String result = instance.getRouteDestinationForPreferredPaymentService();
        Assert.assertEquals("Host", result);
    }

    @Test
    public void testGetRouteDestinationForPreferredPaymentServiceWithOffHostAndNoSecureElement()
        throws NoSuchFieldException, RemoteException {
        CardEmulation instance = createMockedInstance();
        ApduServiceInfo serviceInfo = new ApduServiceInfo(new ResolveInfo(), /* onHost = */ false,
            "", new ArrayList<AidGroup>(), new ArrayList<AidGroup>(), false, 0, 0, "",
            /* offHost = */ null, "");
        when(mEmulation.getPreferredPaymentService(anyInt())).thenReturn(serviceInfo);
        String result = instance.getRouteDestinationForPreferredPaymentService();
        Assert.assertEquals("OffHost", result);
    }

    @Test
    public void testGetRouteDestinationForPreferredPaymentServiceWithOffHostAndSecureElement()
        throws NoSuchFieldException, RemoteException {
        assumeTrue(supportsHardwareForEse());
        CardEmulation instance = createMockedInstance();
        String offHostSecureElement = "OffHost Secure Element";
        ApduServiceInfo serviceInfo = new ApduServiceInfo(new ResolveInfo(), /* onHost = */ false,
            "", new ArrayList<AidGroup>(), new ArrayList<AidGroup>(), false, 0, 0, "",
            /* offHost = */ offHostSecureElement, "");
        when(mEmulation.getPreferredPaymentService(anyInt())).thenReturn(serviceInfo);
        String result = instance.getRouteDestinationForPreferredPaymentService();
        Assert.assertEquals(offHostSecureElement, result);
    }

    @Test
    public void testGetDescriptionForPreferredPaymentService() throws NoSuchFieldException,
        RemoteException {
        CardEmulation instance = createMockedInstance();
        String description = "Preferred Payment Service Description";
        ApduServiceInfo serviceInfo = new ApduServiceInfo(new ResolveInfo(), false,
            /* description */ description, new ArrayList<AidGroup>(), new ArrayList<AidGroup>(),
            false, 0, 0, "", "", "");
        when(mEmulation.getPreferredPaymentService(anyInt())).thenReturn(serviceInfo);
        CharSequence result = instance.getDescriptionForPreferredPaymentService();
        Assert.assertEquals(description, result);
    }

    private Activity createAndResumeActivity() {
        Intent intent
            = new Intent(ApplicationProvider.getApplicationContext(),
                NfcFCardEmulationActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        InstrumentationRegistry.getInstrumentation().callActivityOnResume(activity);
        return activity;
    }

    private CardEmulation createMockedInstance() throws NoSuchFieldException {
        CardEmulation instance = CardEmulation.getInstance(mAdapter);
        FieldSetter.setField(instance, instance.getClass().getDeclaredField("sService"), mEmulation);
        return instance;
    }
}
