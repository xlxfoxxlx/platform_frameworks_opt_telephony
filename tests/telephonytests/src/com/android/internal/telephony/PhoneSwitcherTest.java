/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.IConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.StringNetworkSpecifier;
import android.os.AsyncResult;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.telephony.mocks.ConnectivityServiceMock;
import com.android.internal.telephony.mocks.SubscriptionControllerMock;
import com.android.internal.telephony.mocks.TelephonyRegistryMock;
import com.android.internal.telephony.test.SimulatedCommands;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class PhoneSwitcherTest extends TelephonyTest {
    private static final String LOG_TAG = "PhoneSwitcherTest";

    private static final String[] sNetworkAttributes = new String[] {
            "mobile,0,0,0,-1,true", "mobile_mms,2,0,2,60000,true",
            "mobile_supl,3,0,2,60000,true", "mobile_dun,4,0,2,60000,true",
            "mobile_hipri,5,0,3,60000,true", "mobile_fota,10,0,2,60000,true",
            "mobile_ims,11,0,2,60000,true", "mobile_cbs,12,0,2,60000,true",
            "mobile_ia,14,0,2,-1,true", "mobile_emergency,15,0,2,-1,true"};

    static void failAndStack(String str) {
        fail(str + "\n" + SubscriptionMonitorTest.stack());
    }

    static String stack() {
        StringBuilder sb = new StringBuilder();
        for(StackTraceElement e : Thread.currentThread().getStackTrace()) {
            sb.append(e.toString()).append("\n");
        }
        return sb.toString();
    }

    private static class TestHandler extends Handler {
        public final static int ACTIVE_PHONE_SWITCH = 1;
        public final static int IN_IDLE = 2;

        HandlerThread handlerThread;

        public TestHandler(Looper looper) {
            super(looper);
        }

        public void die() {
            if(handlerThread != null) {
                handlerThread.quit();
                handlerThread = null;
            }
        }

        public void blockTilIdle() {
            Object lock = new Object();
            synchronized (lock) {
                Message msg = this.obtainMessage(IN_IDLE, lock);
                msg.sendToTarget();
                try {
                    lock.wait();
                } catch (InterruptedException e) {}
            }
        }

        public static TestHandler makeHandler() {
            final HandlerThread handlerThread = new HandlerThread("TestHandler");
            handlerThread.start();
            final TestHandler result = new TestHandler(handlerThread.getLooper());
            result.handlerThread = handlerThread;
            return result;
        }

        private boolean objectEquals(Object o1, Object o2) {
            if (o1 == null) return (o2 == null);
            return o1.equals(o2);
        }

        private void failAndStack(String str) {
            SubscriptionMonitorTest.failAndStack(str);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case ACTIVE_PHONE_SWITCH: {
                    AsyncResult ar = (AsyncResult)(msg.obj);
                    if (objectEquals(ar.userObj, mActivePhoneSwitchObject.get()) == false) {
                        failAndStack("Active Phone Switch object is incorrect!");
                    }
                    int count = mActivePhoneSwitchCount.incrementAndGet();
                    Rlog.d(LOG_TAG, "ACTIVE_PHONE_SWITCH, inc to " + count);
                    break;
                }
                case IN_IDLE: {
                    Object lock = msg.obj;
                    synchronized (lock) {
                        lock.notify();
                    }
                    break;
                }
            }
        }

        private final AtomicInteger mActivePhoneSwitchCount = new AtomicInteger(0);
        private final AtomicReference<Object> mActivePhoneSwitchObject =
                new AtomicReference<Object>();

        public void reset() {
            mActivePhoneSwitchCount.set(0);
            mActivePhoneSwitchObject.set(null);
        }

        public void setActivePhoneSwitchObject(Object o) {
            mActivePhoneSwitchObject.set(o);
        }

        public int getActivePhoneSwitchCount() {
            return mActivePhoneSwitchCount.get();
        }
    }

    private void waitABit() {
        try {
            Thread.sleep(250);
        } catch (Exception e) {}
    }

    private String mTestName = "";
    @Mock
    private ITelephonyRegistry.Stub mTelRegistryMock;
    @Mock
    private CommandsInterface mCommandsInterface0;
    @Mock
    private CommandsInterface mCommandsInterface1;
    @Mock
    private IConnectivityManager.Stub mConnectivityService;


    private void log(String str) {
        Rlog.d(LOG_TAG + " " + mTestName, str);
    }

    private NetworkRequest makeDefaultRequest(ConnectivityServiceMock cs, Integer subId) {
        NetworkCapabilities netCap = (new NetworkCapabilities()).
                addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).
                addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED).
                addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
        if (subId != null) {
            netCap.setNetworkSpecifier(new StringNetworkSpecifier(Integer.toString(subId)));
        }
        return cs.requestNetwork(netCap, null, 0, new Binder(), -1);
    }

    private NetworkRequest makeMmsRequest(ConnectivityServiceMock cs, Integer subId) {
        NetworkCapabilities netCap = (new NetworkCapabilities()).
                addCapability(NetworkCapabilities.NET_CAPABILITY_MMS).
                addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED).
                addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
        netCap.setNetworkSpecifier(new StringNetworkSpecifier(Integer.toString(subId)));
        if (subId != null) {
            netCap.setNetworkSpecifier(new StringNetworkSpecifier(Integer.toString(subId)));
        }
        return cs.requestNetwork(netCap, null, 0, new Binder(), -1);
    }

    @Before
    public void setUp() throws Exception {
        super.setUp(getClass().getSimpleName());
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * Test that a single phone case results in our phone being active and the RIL called
     */
    @Test
    @SmallTest
    public void testRegister() throws Exception {
        mTestName = "testRegister";
        final int numPhones = 2;
        final int maxActivePhones = 1;
        final HandlerThread handlerThread = new HandlerThread("PhoneSwitcherTestThread");
        handlerThread.start();
        mContextFixture.putStringArrayResource(com.android.internal.R.array.networkAttributes,
                sNetworkAttributes);
        final Context contextMock = mContextFixture.getTestDouble();
        final ConnectivityServiceMock connectivityServiceMock =
                new ConnectivityServiceMock(contextMock);
        final ConnectivityManager cm =
                new ConnectivityManager(contextMock, connectivityServiceMock);
        mContextFixture.setSystemService(Context.CONNECTIVITY_SERVICE, cm);
        final ITelephonyRegistry.Stub telRegistryMock = new TelephonyRegistryMock();
        final SubscriptionControllerMock subControllerMock =
                new SubscriptionControllerMock(contextMock, telRegistryMock, numPhones);
        final SimulatedCommands[] commandsInterfaces = new SimulatedCommands[numPhones];
        final PhoneMock[] phones = new PhoneMock[numPhones];
        for (int i = 0; i < numPhones; i++) {
            commandsInterfaces[i] = new SimulatedCommands();
        //    phones[i] = new PhoneMock(contextMock, commandsInterfaces[i]);
        }

        PhoneSwitcher phoneSwitcher = new PhoneSwitcher(maxActivePhones, numPhones,
                contextMock, subControllerMock, handlerThread.getLooper(), telRegistryMock,
                commandsInterfaces, phones);

        // verify nothing has been done while there are no inputs
        if (commandsInterfaces[0].isDataAllowed()) fail("data allowed initially");
        if (phoneSwitcher.isPhoneActive(0))        fail("phone active initially");

        connectivityServiceMock.addDefaultRequest();
        waitABit();

        if (commandsInterfaces[0].isDataAllowed()) fail("data allowed after request");
        if (phoneSwitcher.isPhoneActive(0))        fail("phone active after request");

        TestHandler testHandler = TestHandler.makeHandler();
        Object activePhoneSwitchObject = new Object();
        testHandler.setActivePhoneSwitchObject(activePhoneSwitchObject);

        testHandler.blockTilIdle();

        // not registered yet - shouldn't inc
        if (testHandler.getActivePhoneSwitchCount() != 0) {
            fail("pretest of ActivePhoneSwitchCount");
        }
        boolean threw = false;
        try {
            // should throw
            phoneSwitcher.registerForActivePhoneSwitch(2, testHandler,
                    TestHandler.ACTIVE_PHONE_SWITCH, activePhoneSwitchObject);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        if (threw == false) fail("register with bad phoneId didn't throw");

        phoneSwitcher.registerForActivePhoneSwitch(0, testHandler,
                TestHandler.ACTIVE_PHONE_SWITCH,
                activePhoneSwitchObject);
        testHandler.blockTilIdle();

        if (testHandler.getActivePhoneSwitchCount() != 1) {
            fail("post register of ActivePhoneSwitchCount not 1!");
        }

        subControllerMock.setDefaultDataSubId(0);
        testHandler.blockTilIdle();

        if (testHandler.getActivePhoneSwitchCount() != 1) {
            fail("after set of default to 0, ActivePhoneSwitchCount not 1!");
        }
        if (commandsInterfaces[0].isDataAllowed()) fail("data allowed");

        subControllerMock.setSlotSubId(0, 0);
        waitABit();

        if (testHandler.getActivePhoneSwitchCount() != 2) {
            fail("after mapping of 0 to 0, ActivePhoneSwitchCount not 2!");
        }
        if (commandsInterfaces[0].isDataAllowed() == false) fail("data not allowed");

        // now try various things that should cause the active phone to switch:
        // 1 lose default via default sub change
        // 2 gain default via default sub change
        // 3 lose default via sub->phone change
        // 4 gain default via sub->phone change
        // 5 lose default network request
        // 6 gain subscription-specific request
        // 7 lose via sub->phone change
        // 8 gain via sub->phone change
        // 9 lose subscription-specific request
        // 10 don't switch phones when in emergency mode

        // 1 lose default via default sub change
        subControllerMock.setDefaultDataSubId(1);
        waitABit();
        if (testHandler.getActivePhoneSwitchCount() != 3) {
            fail("after set of default to 1, ActivePhoneSwitchCount not 3!");
        }
        if (commandsInterfaces[0].isDataAllowed()) fail("data allowed");

        subControllerMock.setSlotSubId(1, 1);
        waitABit();
        if (testHandler.getActivePhoneSwitchCount() != 3) {
            fail("after mapping of 1 to 1, ActivePhoneSwitchCount not 3!");
        }
        if (commandsInterfaces[0].isDataAllowed()) fail("data allowed");
        if (commandsInterfaces[1].isDataAllowed() == false) fail("data not allowed");

        // 2 gain default via default sub change
        subControllerMock.setDefaultDataSubId(0);
        waitABit();
        if (testHandler.getActivePhoneSwitchCount() != 4) {
            fail("after set of default to 0, ActivePhoneSwitchCount not 4!");
        }
        if (commandsInterfaces[1].isDataAllowed()) fail("data allowed");
        if (commandsInterfaces[0].isDataAllowed() == false) fail("data not allowed");

        // 3 lose default via sub->phone change
        subControllerMock.setSlotSubId(0, 2);
        waitABit();

        if (testHandler.getActivePhoneSwitchCount() != 5) {
            fail("after mapping of 0 to 2, ActivePhoneSwitchCount not 5!");
        }
        if (commandsInterfaces[0].isDataAllowed()) fail("data allowed");
        if (commandsInterfaces[1].isDataAllowed()) fail("data allowed");

        // 4 gain default via sub->phone change
        subControllerMock.setSlotSubId(0, 0);
        waitABit();
        if (testHandler.getActivePhoneSwitchCount() != 6) {
            fail("after mapping of 0 to 0, ActivePhoneSwitchCount not 6!");
        }
        if (commandsInterfaces[0].isDataAllowed() == false) fail("data not allowed");
        if (commandsInterfaces[1].isDataAllowed()) fail("data allowed");

        // 5 lose default network request
        connectivityServiceMock.removeDefaultRequest();
        waitABit();
        if (testHandler.getActivePhoneSwitchCount() != 7) {
            fail("after loss of network request, ActivePhoneSwitchCount not 7!");
        }
        if (commandsInterfaces[0].isDataAllowed()) fail("data allowed");
        if (commandsInterfaces[1].isDataAllowed()) fail("data allowed");

        // 6 gain subscription-specific request
        NetworkRequest request = makeDefaultRequest(connectivityServiceMock, 0);
        waitABit();
        if (testHandler.getActivePhoneSwitchCount() != 8) {
            fail("after gain of network request, ActivePhoneSwitchCount not 8!");
        }
        if (commandsInterfaces[0].isDataAllowed() == false) fail("data not allowed");
        if (commandsInterfaces[1].isDataAllowed()) fail("data allowed");

        // 7 lose via sub->phone change
        subControllerMock.setSlotSubId(0, 1);
        waitABit();
        if (testHandler.getActivePhoneSwitchCount() != 9) {
            fail("after loss of request due to subId map change, ActivePhoneSwitchCount not 9!");
        }
        if (commandsInterfaces[0].isDataAllowed()) fail("data allowed");
        if (commandsInterfaces[1].isDataAllowed()) fail("data allowed");

        // 8 gain via sub->phone change
        subControllerMock.setSlotSubId(0, 0);
        waitABit();
        if (testHandler.getActivePhoneSwitchCount() != 10) {
            fail("after gain of request due to subId map change, ActivePhoneSwitchCount not 10!");
        }
        if (commandsInterfaces[0].isDataAllowed() == false) fail("data not allowed");
        if (commandsInterfaces[1].isDataAllowed()) fail("data allowed");

        // 9 lose subscription-specific request
        connectivityServiceMock.releaseNetworkRequest(request);
        waitABit();
        if (testHandler.getActivePhoneSwitchCount() != 11) {
            fail("after release of request, ActivePhoneSwitchCount not 11!");
        }
        if (commandsInterfaces[0].isDataAllowed()) fail("data allowed");
        if (commandsInterfaces[1].isDataAllowed()) fail("data allowed");

        // 10 don't switch phones when in emergency mode
        // not ready yet - Phone turns out to be hard to stub out
//        phones[0].setInEmergencyCall(true);
//        connectivityServiceMock.addDefaultRequest();
//        waitABit();
//        if (testHandler.getActivePhoneSwitchCount() != 11) {
//            fail("after release of request, ActivePhoneSwitchCount not 11!");
//        }
//        if (commandsInterfaces[0].isDataAllowed()) fail("data allowed");
//        if (commandsInterfaces[1].isDataAllowed()) fail("data allowed");
//
//        phones[0].setInEmergencyCall(false);
//        connectivityServiceMock.addDefaultRequest();
//        waitABit();
//        if (testHandler.getActivePhoneSwitchCount() != 12) {
//            fail("after release of request, ActivePhoneSwitchCount not 11!");
//        }
//        if (commandsInterfaces[0].isDataAllowed()) fail("data allowed");
//        if (commandsInterfaces[1].isDataAllowed()) fail("data allowed");

        for (int i = 0; i < numPhones; i++) {
            commandsInterfaces[i].dispose();
        }

        connectivityServiceMock.die();
        testHandler.die();
        handlerThread.quit();
    }

    /**
     * Test a multi-sim case with limited active phones:
     * - lose default via default sub change
     * - lose default via sub->phone change
     * - gain default via sub->phone change
     * - gain default via default sub change
     * - lose default network request
     * - gain subscription-specific request
     * - lose via sub->phone change
     * - gain via sub->phone change
     * - lose subscription-specific request
     * - tear down low priority phone when new request comes in
     * - tear down low priority phone when sub change causes split
     * - bring up low priority phone when sub change causes join
     * - don't switch phones when in emergency mode
     */
    @Test
    @SmallTest
    public void testPrioritization() throws Exception {
        mTestName = "testPrioritization";
        final int numPhones = 2;
        final int maxActivePhones = 1;
        final HandlerThread handlerThread = new HandlerThread("PhoneSwitcherTestThread");
        handlerThread.start();
        mContextFixture.putStringArrayResource(com.android.internal.R.array.networkAttributes,
                sNetworkAttributes);
        final Context contextMock = mContextFixture.getTestDouble();
        final ConnectivityServiceMock connectivityServiceMock =
            new ConnectivityServiceMock(contextMock);
        final ConnectivityManager cm =
                new ConnectivityManager(contextMock, connectivityServiceMock);
        mContextFixture.setSystemService(Context.CONNECTIVITY_SERVICE, cm);
        final ITelephonyRegistry.Stub telRegistryMock = new TelephonyRegistryMock();
        final SubscriptionControllerMock subControllerMock =
                new SubscriptionControllerMock(contextMock, telRegistryMock, numPhones);
        final SimulatedCommands[] commandsInterfaces = new SimulatedCommands[numPhones];
        final PhoneMock[] phones = new PhoneMock[numPhones];
        for (int i = 0; i < numPhones; i++) {
            commandsInterfaces[i] = new SimulatedCommands();
        }

        PhoneSwitcher phoneSwitcher = new PhoneSwitcher(maxActivePhones, numPhones,
                contextMock, subControllerMock, handlerThread.getLooper(), telRegistryMock,
                commandsInterfaces, phones);

        TestHandler testHandler = TestHandler.makeHandler();
        Object activePhoneSwitchObject = new Object();
        testHandler.setActivePhoneSwitchObject(activePhoneSwitchObject);

        connectivityServiceMock.addDefaultRequest();
        subControllerMock.setSlotSubId(0, 0);
        subControllerMock.setSlotSubId(1, 1);
        subControllerMock.setDefaultDataSubId(0);
        waitABit();
        phoneSwitcher.registerForActivePhoneSwitch(0, testHandler, TestHandler.ACTIVE_PHONE_SWITCH,
                activePhoneSwitchObject);
        waitABit();
        // verify initial conditions
        if (testHandler.getActivePhoneSwitchCount() != 1) {
            fail("Initial conditions not met: ActivePhoneSwitchCount not 1! " +
                    testHandler.getActivePhoneSwitchCount());
        }
        if (commandsInterfaces[0].isDataAllowed() == false) fail("data not allowed");
        if (commandsInterfaces[1].isDataAllowed()) fail("data allowed");

        // now start a higher priority conneciton on the other sub
        NetworkRequest request = makeMmsRequest(connectivityServiceMock, 1);
        waitABit();
        if (testHandler.getActivePhoneSwitchCount() != 2) {
            fail("after gain of network request, ActivePhoneSwitchCount not 2!");
        }
        if (commandsInterfaces[0].isDataAllowed()) fail("data allowed");
        if (commandsInterfaces[1].isDataAllowed() == false) fail("data not allowed");

        for (int i = 0; i < numPhones; i++) {
            commandsInterfaces[i].dispose();
        }

        connectivityServiceMock.die();
        testHandler.die();
        handlerThread.quit();
    }

    /**
     * Verify we don't send spurious DATA_ALLOWED calls when another NetworkFactory
     * wins (ie, switch to wifi).
     */
    @Test
    @SmallTest
    public void testHigherPriorityDefault() throws Exception {
        mTestName = "testPrioritization";
        final int numPhones = 2;
        final int maxActivePhones = 1;
        final HandlerThread handlerThread = new HandlerThread("PhoneSwitcherTestThread");
        handlerThread.start();
        mContextFixture.putStringArrayResource(com.android.internal.R.array.networkAttributes,
                sNetworkAttributes);
        final Context contextMock = mContextFixture.getTestDouble();
        final ConnectivityServiceMock connectivityServiceMock =
                new ConnectivityServiceMock(contextMock);
        final ConnectivityManager cm =
                new ConnectivityManager(contextMock, connectivityServiceMock);
        mContextFixture.setSystemService(Context.CONNECTIVITY_SERVICE, cm);
        final ITelephonyRegistry.Stub telRegistryMock = new TelephonyRegistryMock();
        final SubscriptionControllerMock subControllerMock =
                new SubscriptionControllerMock(contextMock, telRegistryMock, numPhones);
        final SimulatedCommands[] commandsInterfaces = new SimulatedCommands[numPhones];
        final PhoneMock[] phones = new PhoneMock[numPhones];
        for (int i = 0; i < numPhones; i++) {
            commandsInterfaces[i] = new SimulatedCommands();
        }

        PhoneSwitcher phoneSwitcher = new PhoneSwitcher(maxActivePhones, numPhones,
                contextMock, subControllerMock, handlerThread.getLooper(), telRegistryMock,
                commandsInterfaces, phones);

        TestHandler testHandler = TestHandler.makeHandler();
        Object activePhoneSwitchObject = new Object();
        testHandler.setActivePhoneSwitchObject(activePhoneSwitchObject);

        connectivityServiceMock.addDefaultRequest();
        subControllerMock.setSlotSubId(0, 0);
        subControllerMock.setSlotSubId(1, 1);
        subControllerMock.setDefaultDataSubId(0);
        waitABit();

        // Phone 0 should be active
        if (commandsInterfaces[0].isDataAllowed() == false) fail("data not allowed");
        if (commandsInterfaces[1].isDataAllowed()) fail("data allowed");

        connectivityServiceMock.setCurrentScoreForRequest(connectivityServiceMock.defaultRequest,
                100);
        waitABit();

        // should be no change
        if (commandsInterfaces[0].isDataAllowed() == false) fail("data not allowed");
        if (commandsInterfaces[1].isDataAllowed()) fail("data allowed");

        connectivityServiceMock.setCurrentScoreForRequest(connectivityServiceMock.defaultRequest,
                0);
        waitABit();

        // should be no change
        if (commandsInterfaces[0].isDataAllowed() == false) fail("data not allowed");
        if (commandsInterfaces[1].isDataAllowed()) fail("data allowed");

        for (int i = 0; i < numPhones; i++) {
            commandsInterfaces[i].dispose();
        }

        connectivityServiceMock.die();
        testHandler.die();
        handlerThread.quit();
    }

    /**
     * Verify testSetPreferredData.
     * When preferredData is set, it overwrites defaultData sub to be active sub in single
     * active phone mode. If it's unset (to DEFAULT_SUBSCRIPTION_ID), defaultData sub becomes
     * active one.
     */
    @Test
    @SmallTest
    public void testSetPreferredData() throws Exception {
        mTestName = "testSetPreferredData";
        final int numPhones = 2;
        final int maxActivePhones = 1;
        mContextFixture.putStringArrayResource(com.android.internal.R.array.networkAttributes,
                sNetworkAttributes);
        final CommandsInterface[] commandsInterfaces = new CommandsInterface[numPhones];
        commandsInterfaces[0] = mCommandsInterface0;
        commandsInterfaces[1] = mCommandsInterface1;

        final ConnectivityServiceMock connectivityServiceMock =
                new ConnectivityServiceMock(mContext);
        final ConnectivityManager cm =
                new ConnectivityManager(mContext, connectivityServiceMock);
        mContextFixture.setSystemService(Context.CONNECTIVITY_SERVICE, cm);
        final HandlerThread handlerThread = new HandlerThread("PhoneSwitcherTestThread");
        final PhoneMock[] phones = new PhoneMock[numPhones];

        // Phone 0 has sub 1, phone 1 has sub 2.
        // Sub 1 is default data sub.
        // Both are active subscriptions are active sub, as they are in both active slots.
        doReturn(1).when(mSubscriptionController)
                .getDefaultDataSubId();
        doReturn(1).when(mSubscriptionController)
                .getSubIdUsingPhoneId(0);
        doReturn(2).when(mSubscriptionController)
                .getSubIdUsingPhoneId(1);
        doReturn(true).when(mSubscriptionController)
                .isActiveSubId(1);
        doReturn(true).when(mSubscriptionController)
                .isActiveSubId(2);

        handlerThread.start();

        PhoneSwitcher phoneSwitcher = new PhoneSwitcher(maxActivePhones, numPhones,
                mContext, mSubscriptionController, handlerThread.getLooper(), mTelRegistryMock,
                commandsInterfaces, phones);

        // Notify phoneSwitcher about default data sub and default network request.
        sendDefaultDataSubChanged();
        NetworkRequest request = makeDefaultRequest(connectivityServiceMock, null);
        waitABit();
        // Phone 0 (sub 1) should be activated as it has default data sub.
        verify(mCommandsInterface0).setDataAllowed(eq(true), any());
        reset(mCommandsInterface0);

        // Set sub 2 as preferred sub should make phone 1 activated and phone 0 deactivated.
        phoneSwitcher.setPreferredData(2);
        waitABit();
        verify(mCommandsInterface0).setDataAllowed(eq(false), any());
        verify(mCommandsInterface1).setDataAllowed(eq(true), any());
        reset(mCommandsInterface0);
        reset(mCommandsInterface1);

        // Unset preferred sub should make default data sub (phone 0 / sub 1) activated again.
        phoneSwitcher.setPreferredData(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID);
        waitABit();
        verify(mCommandsInterface0, times(1)).setDataAllowed(eq(true), any());
        verify(mCommandsInterface1, times(1)).setDataAllowed(eq(false), any());

        handlerThread.quit();
    }

    private void sendDefaultDataSubChanged() {
        final Intent intent = new Intent(TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED);
        mContext.sendBroadcast(intent);
    }
}
