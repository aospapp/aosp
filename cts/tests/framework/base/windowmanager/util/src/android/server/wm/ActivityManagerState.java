/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package android.server.wm;

import static android.app.ActivityTaskManager.INVALID_STACK_ID;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.server.wm.ComponentNameUtils.getActivityName;
import static android.server.wm.ProtoExtractors.extract;
import static android.server.wm.StateLogger.log;
import static android.server.wm.StateLogger.logE;

import static androidx.test.InstrumentationRegistry.getInstrumentation;

import android.content.ComponentName;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.SparseArray;

import androidx.annotation.Nullable;

import com.android.server.am.nano.ActivityDisplayProto;
import com.android.server.am.nano.ActivityManagerServiceDumpActivitiesProto;
import com.android.server.am.nano.ActivityRecordProto;
import com.android.server.am.nano.ActivityStackProto;
import com.android.server.am.nano.ActivityStackSupervisorProto;
import com.android.server.am.nano.KeyguardControllerProto;
import com.android.server.am.nano.TaskRecordProto;
import com.android.server.wm.nano.ConfigurationContainerProto;

import com.google.protobuf.nano.InvalidProtocolBufferNanoException;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ActivityManagerState {

    public static final int DUMP_MODE_ACTIVITIES = 0;

    public static final String STATE_INITIALIZING = "INITIALIZING";
    public static final String STATE_RESUMED = "RESUMED";
    public static final String STATE_PAUSED = "PAUSED";
    public static final String STATE_STOPPED = "STOPPED";
    public static final String STATE_DESTROYED = "DESTROYED";

    private static final String DUMPSYS_ACTIVITY_ACTIVITIES = "dumpsys activity --proto activities";

    // Displays in z-order with the top most at the front of the list, starting with primary.
    private final List<ActivityDisplay> mDisplays = new ArrayList<>();
    // Stacks in z-order with the top most at the front of the list, starting with primary display.
    private final List<ActivityStack> mStacks = new ArrayList<>();
    private KeyguardControllerState mKeyguardControllerState;
    private final List<String> mPendingActivities = new ArrayList<>();
    private int mTopFocusedStackId = -1;
    private Boolean mIsHomeRecentsComponent;
    private String mTopResumedActivityRecord = null;
    final List<String> mResumedActivitiesInStacks = new ArrayList<>();
    final List<String> mResumedActivitiesInDisplays = new ArrayList<>();

    void computeState() {
        computeState(DUMP_MODE_ACTIVITIES);
    }

    void computeState(int dumpMode) {
        // It is possible the system is in the middle of transition to the right state when we get
        // the dump. We try a few times to get the information we need before giving up.
        int retriesLeft = 3;
        boolean retry = false;
        byte[] dump = null;

        log("==============================");
        log("     ActivityManagerState     ");
        log("==============================");

        do {
            if (retry) {
                log("***Incomplete AM state. Retrying...");
                // Wait half a second between retries for activity manager to finish transitioning.
                SystemClock.sleep(500);
            }

            String dumpsysCmd = "";
            switch (dumpMode) {
                case DUMP_MODE_ACTIVITIES:
                    dumpsysCmd = DUMPSYS_ACTIVITY_ACTIVITIES;
                    break;
            }

            dump = executeShellCommand(dumpsysCmd);
            try {
                parseSysDumpProto(dump);
            } catch (InvalidProtocolBufferNanoException ex) {
                throw new RuntimeException("Failed to parse dumpsys:\n"
                        + new String(dump, StandardCharsets.UTF_8), ex);
            }

            retry = mStacks.isEmpty() || mTopFocusedStackId == -1
                    || (mTopResumedActivityRecord == null || mResumedActivitiesInStacks.isEmpty())
                    && !mKeyguardControllerState.keyguardShowing;
        } while (retry && retriesLeft-- > 0);

        if (mStacks.isEmpty()) {
            logE("No stacks found...");
        }
        if (mTopFocusedStackId == -1) {
            logE("No focused stack found...");
        }
        if (mTopResumedActivityRecord == null) {
            logE("No focused activity found...");
        }
        if (mResumedActivitiesInStacks.isEmpty()) {
            logE("No resumed activities found...");
        }
    }

    private byte[] executeShellCommand(String cmd) {
        try {
            ParcelFileDescriptor pfd = getInstrumentation().getUiAutomation()
                    .executeShellCommand(cmd);
            byte[] buf = new byte[512];
            int bytesRead;
            FileInputStream fis = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            while ((bytesRead = fis.read(buf)) != -1) {
                stdout.write(buf, 0, bytesRead);
            }
            fis.close();
            return stdout.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void parseSysDumpProto(byte[] sysDump) throws InvalidProtocolBufferNanoException {
        reset();

        ActivityStackSupervisorProto state = ActivityManagerServiceDumpActivitiesProto.parseFrom(sysDump)
                .activityStackSupervisor;
        for (int i = 0; i < state.displays.length; i++) {
            ActivityDisplayProto activityDisplay = state.displays[i];
            mDisplays.add(new ActivityDisplay(activityDisplay, this));
        }
        mKeyguardControllerState = new KeyguardControllerState(state.keyguardController);
        mTopFocusedStackId = state.focusedStackId;
        if (state.resumedActivity != null) {
            mTopResumedActivityRecord = state.resumedActivity.title;
        }
        mIsHomeRecentsComponent = new Boolean(state.isHomeRecentsComponent);

        for (int i = 0; i < state.pendingActivities.length; i++) {
            mPendingActivities.add(state.pendingActivities[i].title);
        }
    }

    private void reset() {
        mDisplays.clear();
        mStacks.clear();
        mTopFocusedStackId = -1;
        mTopResumedActivityRecord = null;
        mResumedActivitiesInStacks.clear();
        mResumedActivitiesInDisplays.clear();
        mKeyguardControllerState = null;
        mIsHomeRecentsComponent = null;
        mPendingActivities.clear();
    }

    /**
     * @return Whether the home activity is the recents component.
     */
    boolean isHomeRecentsComponent() {
        if (mIsHomeRecentsComponent == null) {
            computeState();
        }
        return mIsHomeRecentsComponent;
    }

    ActivityDisplay getDisplay(int displayId) {
        for (ActivityDisplay display : mDisplays) {
            if (display.mId == displayId) {
                return display;
            }
        }
        return null;
    }

    int getFrontStackId(int displayId) {
        return getDisplay(displayId).mStacks.get(0).mStackId;
    }

    int getFrontStackActivityType(int displayId) {
        return getDisplay(displayId).mStacks.get(0).getActivityType();
    }

    int getFrontStackWindowingMode(int displayId) {
        return getDisplay(displayId).mStacks.get(0).getWindowingMode();
    }

    public String getTopActivityName(int displayId) {
        if (!getDisplay(displayId).mStacks.isEmpty()) {
            final ActivityStack topStack = getDisplay(displayId).mStacks.get(0);
            if (!topStack.mTasks.isEmpty()) {
                final ActivityTask topTask = topStack.mTasks.get(0);
                if (!topTask.mActivities.isEmpty()) {
                    return topTask.mActivities.get(0).name;
                }
            }
        }
        return null;
    }

    int getFocusedStackId() {
        return mTopFocusedStackId;
    }

    int getFocusedStackActivityType() {
        final ActivityStack stack = getStackById(mTopFocusedStackId);
        return stack != null ? stack.getActivityType() : ACTIVITY_TYPE_UNDEFINED;
    }

    int getFocusedStackWindowingMode() {
        final ActivityStack stack = getStackById(mTopFocusedStackId);
        return stack != null ? stack.getWindowingMode() : WINDOWING_MODE_UNDEFINED;
    }

    String getFocusedActivity() {
        return mTopResumedActivityRecord;
    }

    int getResumedActivitiesCount() {
        return mResumedActivitiesInStacks.size();
    }

    int getResumedActivitiesCountInPackage(String packageName) {
        final String componentPrefix = packageName + "/";
        int count = 0;
        for (int i = mDisplays.size() - 1; i >= 0; --i) {
            final ArrayList<ActivityStack> mStacks = mDisplays.get(i).getStacks();
            for (int j = mStacks.size() - 1; j >= 0; --j) {
                final String resumedActivity = mStacks.get(j).mResumedActivity;
                if (resumedActivity != null && resumedActivity.startsWith(componentPrefix)) {
                    count++;
                }
            }
        }
        return count;
    }

    String getResumedActivityOnDisplay(int displayId) {
        return getDisplay(displayId).mResumedActivity;
    }

    public KeyguardControllerState getKeyguardControllerState() {
        return mKeyguardControllerState;
    }

    boolean containsStack(int stackId) {
        return getStackById(stackId) != null;
    }

    boolean containsStack(int windowingMode, int activityType) {
        for (ActivityStack stack : mStacks) {
            if (activityType != ACTIVITY_TYPE_UNDEFINED
                    && activityType != stack.getActivityType()) {
                continue;
            }
            if (windowingMode != WINDOWING_MODE_UNDEFINED
                    && windowingMode != stack.getWindowingMode()) {
                continue;
            }
            return true;
        }
        return false;
    }

    ActivityStack getStackById(int stackId) {
        for (ActivityStack stack : mStacks) {
            if (stackId == stack.mStackId) {
                return stack;
            }
        }
        return null;
    }

    ActivityStack getStackByActivityType(int activityType) {
        for (ActivityStack stack : mStacks) {
            if (activityType == stack.getActivityType()) {
                return stack;
            }
        }
        return null;
    }

    ActivityStack getStandardStackByWindowingMode(int windowingMode) {
        for (ActivityStack stack : mStacks) {
            if (stack.getActivityType() != ACTIVITY_TYPE_STANDARD) {
                continue;
            }
            if (stack.getWindowingMode() == windowingMode) {
                return stack;
            }
        }
        return null;
    }

    public int getStandardTaskCountByWindowingMode(int windowingMode) {
        int count = 0;
        for (ActivityStack stack : mStacks) {
            if (stack.getActivityType() != ACTIVITY_TYPE_STANDARD) {
                continue;
            }
            if (stack.getWindowingMode() == windowingMode) {
                count += stack.mTasks.size();
            }
        }
        return count;
    }

    /** Get the stack position on its display. */
    int getStackIndexByActivityType(int activityType) {
        for (ActivityDisplay display : mDisplays) {
            for (int i = 0; i < display.mStacks.size(); i++) {
                if (activityType == display.mStacks.get(i).getActivityType()) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Get the stack position on its display. */
    int getStackIndexByActivity(ComponentName activityName) {
        final String fullName = getActivityName(activityName);

        for (ActivityDisplay display : mDisplays) {
            for (int i = display.mStacks.size() - 1; i >= 0; --i) {
                final ActivityStack stack = display.mStacks.get(i);
                for (ActivityTask task : stack.mTasks) {
                    for (Activity activity : task.mActivities) {
                        if (activity.name.equals(fullName)) {
                            return i;
                        }
                    }
                }
            }
        }
        return -1;
    }

    /** Get display id by activity on it. */
    int getDisplayByActivity(ComponentName activityComponent) {
        final ActivityManagerState.ActivityTask task = getTaskByActivity(activityComponent);
        if (task == null) {
            return -1;
        }
        return getStackById(task.mStackId).mDisplayId;
    }

    List<ActivityDisplay> getDisplays() {
        return new ArrayList<>(mDisplays);
    }

    public List<ActivityStack> getStacks() {
        return new ArrayList<>(mStacks);
    }

    int getStackCount() {
        return mStacks.size();
    }

    int getDisplayCount() {
        return mDisplays.size();
    }

    public boolean containsActivity(ComponentName activityName) {
        final String fullName = getActivityName(activityName);
        for (ActivityStack stack : mStacks) {
            for (ActivityTask task : stack.mTasks) {
                for (Activity activity : task.mActivities) {
                    if (activity.name.equals(fullName)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public boolean containsNoneOf(Iterable<ComponentName> activityNames) {
        for (ComponentName activityName : activityNames) {
            String fullName = getActivityName(activityName);

            for (ActivityStack stack : mStacks) {
                for (ActivityTask task : stack.mTasks) {
                    for (Activity activity : task.mActivities) {
                        if (activity.name.equals(fullName)) {
                            return false;
                        }
                    }
                }
            }
        }

        return true;
    }

    boolean containsActivityInWindowingMode(ComponentName activityName, int windowingMode) {
        final String fullName = getActivityName(activityName);
        for (ActivityStack stack : mStacks) {
            for (ActivityTask task : stack.mTasks) {
                for (Activity activity : task.mActivities) {
                    if (activity.name.equals(fullName)
                            && activity.getWindowingMode() == windowingMode) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    boolean isActivityVisible(ComponentName activityName) {
        final String fullName = getActivityName(activityName);
        for (ActivityStack stack : mStacks) {
            for (ActivityTask task : stack.mTasks) {
                for (Activity activity : task.mActivities) {
                    if (activity.name.equals(fullName)) {
                        return activity.visible;
                    }
                }
            }
        }
        return false;
    }

    boolean isActivityTranslucent(ComponentName activityName) {
        final String fullName = getActivityName(activityName);
        for (ActivityStack stack : mStacks) {
            for (ActivityTask task : stack.mTasks) {
                for (Activity activity : task.mActivities) {
                    if (activity.name.equals(fullName)) {
                        return activity.translucent;
                    }
                }
            }
        }
        return false;
    }

    boolean isBehindOpaqueActivities(ComponentName activityName) {
        final String fullName = getActivityName(activityName);
        for (ActivityStack stack : mStacks) {
            for (ActivityTask task : stack.mTasks) {
                for (Activity activity : task.mActivities) {
                    if (activity.name.equals(fullName)) {
                        return false;
                    }
                    if (!activity.translucent) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    boolean containsStartedActivities() {
        for (ActivityStack stack : mStacks) {
            for (ActivityTask task : stack.mTasks) {
                for (Activity activity : task.mActivities) {
                    if (!activity.state.equals(STATE_STOPPED)
                            && !activity.state.equals(STATE_DESTROYED)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    boolean hasActivityState(ComponentName activityName, String activityState) {
        final String fullName = getActivityName(activityName);
        for (ActivityStack stack : mStacks) {
            for (ActivityTask task : stack.mTasks) {
                for (Activity activity : task.mActivities) {
                    if (activity.name.equals(fullName)) {
                        return activity.state.equals(activityState);
                    }
                }
            }
        }
        return false;
    }

    int getActivityProcId(ComponentName activityName) {
        final String fullName = getActivityName(activityName);
        for (ActivityStack stack : mStacks) {
            for (ActivityTask task : stack.mTasks) {
                for (Activity activity : task.mActivities) {
                    if (activity.name.equals(fullName)) {
                        return activity.procId;
                    }
                }
            }
        }
        return -1;
    }

    boolean isHomeActivityVisible() {
        final Activity homeActivity = getHomeActivity();
        return homeActivity != null && homeActivity.visible;
    }

    boolean isRecentsActivityVisible() {
        final Activity recentsActivity = getRecentsActivity();
        return recentsActivity != null && recentsActivity.visible;
    }

    ComponentName getHomeActivityName() {
        Activity activity = getHomeActivity();
        if (activity == null) {
            return null;
        }
        return ComponentName.unflattenFromString(activity.name);
    }

    ActivityTask getHomeTask() {
        final ActivityStack homeStack = getStackByActivityType(ACTIVITY_TYPE_HOME);
        if (homeStack != null && !homeStack.mTasks.isEmpty()) {
            return homeStack.mTasks.get(0);
        }
        return null;
    }

    private ActivityTask getRecentsTask() {
        final ActivityStack recentsStack = getStackByActivityType(ACTIVITY_TYPE_RECENTS);
        if (recentsStack != null && !recentsStack.mTasks.isEmpty()) {
            return recentsStack.mTasks.get(0);
        }
        return null;
    }

    private Activity getHomeActivity() {
        final ActivityTask homeTask = getHomeTask();
        return homeTask != null ? homeTask.mActivities.get(homeTask.mActivities.size() - 1) : null;
    }

    private Activity getRecentsActivity() {
        final ActivityTask recentsTask = getRecentsTask();
        return recentsTask != null ? recentsTask.mActivities.get(recentsTask.mActivities.size() - 1)
                : null;
    }

    public int getStackIdByActivity(ComponentName activityName) {
        final ActivityTask task = getTaskByActivity(activityName);
        return  (task == null) ? INVALID_STACK_ID : task.mStackId;
    }

    public ActivityTask getTaskByActivity(ComponentName activityName) {
        return getTaskByActivityInternal(getActivityName(activityName), WINDOWING_MODE_UNDEFINED);
    }

    ActivityTask getTaskByActivity(ComponentName activityName, int windowingMode) {
        return getTaskByActivityInternal(getActivityName(activityName), windowingMode);
    }

    private ActivityTask getTaskByActivityInternal(String fullName, int windowingMode) {
        for (ActivityStack stack : mStacks) {
            if (windowingMode == WINDOWING_MODE_UNDEFINED
                    || windowingMode == stack.getWindowingMode()) {
                for (ActivityTask task : stack.mTasks) {
                    for (Activity activity : task.mActivities) {
                        if (activity.name.equals(fullName)) {
                            return task;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Get the number of activities in the task, with the option to count only activities with
     * specific name.
     * @param taskId Id of the task where we're looking for the number of activities.
     * @param activityName Optional name of the activity we're interested in.
     * @return Number of all activities in the task if activityName is {@code null}, otherwise will
     *         report number of activities that have specified name.
     */
    public int getActivityCountInTask(int taskId, @Nullable ComponentName activityName) {
        // If activityName is null, count all activities in the task.
        // Otherwise count activities that have specified name.
        for (ActivityStack stack : mStacks) {
            for (ActivityTask task : stack.mTasks) {
                if (task.mTaskId == taskId) {
                    if (activityName == null) {
                        return task.mActivities.size();
                    }
                    final String fullName = getActivityName(activityName);
                    int count = 0;
                    for (Activity activity : task.mActivities) {
                        if (activity.name.equals(fullName)) {
                            count++;
                        }
                    }
                    return count;
                }
            }
        }
        return 0;
    }

    public int getStackCounts() {
        return mStacks.size();
    }

    boolean pendingActivityContain(ComponentName activityName) {
        return mPendingActivities.contains(getActivityName(activityName));
    }

    public static class ActivityDisplay extends ActivityContainer {

        public int mId;
        ArrayList<ActivityStack> mStacks = new ArrayList<>();
        int mFocusedStackId;
        String mResumedActivity;
        boolean mSingleTaskInstance;

        ActivityDisplay(ActivityDisplayProto proto, ActivityManagerState amState) {
            super(proto.configurationContainer);
            mId = proto.id;
            mFocusedStackId = proto.focusedStackId;
            mSingleTaskInstance = proto.singleTaskInstance;
            if (proto.resumedActivity != null) {
                mResumedActivity = proto.resumedActivity.title;
                amState.mResumedActivitiesInDisplays.add(mResumedActivity);
            }
            for (int i = 0; i < proto.stacks.length; i++) {
                ActivityStack activityStack = new ActivityStack(proto.stacks[i]);
                mStacks.add(activityStack);
                // Also update activity manager state
                amState.mStacks.add(activityStack);
                if (activityStack.mResumedActivity != null) {
                    amState.mResumedActivitiesInStacks.add(activityStack.mResumedActivity);
                }
            }
        }

        boolean containsActivity(ComponentName activityName) {
            final String fullName = getActivityName(activityName);
            for (ActivityStack stack : mStacks) {
                for (ActivityTask task : stack.mTasks) {
                    for (Activity activity : task.mActivities) {
                        if (activity.name.equals(fullName)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        public ArrayList<ActivityStack> getStacks() {
            return mStacks;
        }
    }

    public static class ActivityStack extends ActivityContainer {

        public int mDisplayId;
        int mStackId;
        String mResumedActivity;
        ArrayList<ActivityTask> mTasks = new ArrayList<>();

        ActivityStack(ActivityStackProto proto) {
            super(proto.configurationContainer);
            mStackId = proto.id;
            mDisplayId = proto.displayId;
            mBounds = extract(proto.bounds);
            mFullscreen = proto.fullscreen;
            for (int i = 0; i < proto.tasks.length; i++) {
                mTasks.add(new ActivityTask(proto.tasks[i]));
            }
            if (proto.resumedActivity != null) {
                mResumedActivity = proto.resumedActivity.title;
            }
        }

        /**
         * @return the bottom task in the stack.
         */
        ActivityTask getBottomTask() {
            if (!mTasks.isEmpty()) {
                // NOTE: Unlike the ActivityManager internals, we dump the state from top to bottom,
                //       so the indices are inverted
                return mTasks.get(mTasks.size() - 1);
            }
            return null;
        }

        /**
         * @return the top task in the stack.
         */
        ActivityTask getTopTask() {
            if (!mTasks.isEmpty()) {
                // NOTE: Unlike the ActivityManager internals, we dump the state from top to bottom,
                //       so the indices are inverted
                return mTasks.get(0);
            }
            return null;
        }

        public List<ActivityTask> getTasks() {
            return new ArrayList<>(mTasks);
        }

        ActivityTask getTask(int taskId) {
            for (ActivityTask task : mTasks) {
                if (taskId == task.mTaskId) {
                    return task;
                }
            }
            return null;
        }

        public int getStackId() {
            return mStackId;
        }

        public String getResumedActivity() {
            return mResumedActivity;
        }
    }

    public static class ActivityTask extends ActivityContainer {

        int mTaskId;
        int mStackId;
        Rect mLastNonFullscreenBounds;
        String mRealActivity;
        String mOrigActivity;
        ArrayList<Activity> mActivities = new ArrayList<>();
        int mTaskType = -1;
        private int mResizeMode;

        ActivityTask(TaskRecordProto proto) {
            super(proto.configurationContainer);
            mTaskId = proto.id;
            mStackId = proto.stackId;
            mLastNonFullscreenBounds = extract(proto.lastNonFullscreenBounds);
            mRealActivity = proto.realActivity;
            mOrigActivity = proto.origActivity;
            mTaskType = proto.activityType;
            mResizeMode = proto.resizeMode;
            mFullscreen = proto.fullscreen;
            mBounds = extract(proto.bounds);
            mMinWidth = proto.minWidth;
            mMinHeight = proto.minHeight;
            for (int i = 0;  i < proto.activities.length; i++) {
                mActivities.add(new Activity(proto.activities[i]));
            }
        }

        public int getResizeMode() {
            return mResizeMode;
        }

        public int getTaskId() {
            return mTaskId;
        }

        public ArrayList<Activity> getActivities() {
            return mActivities;
        }
    }

    public static class Activity extends ActivityContainer {

        String name;
        String state;
        boolean visible;
        boolean frontOfTask;
        int procId = -1;
        public boolean translucent;

        Activity(ActivityRecordProto proto) {
            super(proto.configurationContainer);
            name = proto.identifier.title;
            state = proto.state;
            visible = proto.visible;
            frontOfTask = proto.frontOfTask;
            if (proto.procId != 0) {
                procId = proto.procId;
            }
            translucent = proto.translucent;
        }

        public String getName() {
            return name;
        }

        public String getState() {
            return state;
        }
    }

    static abstract class ActivityContainer extends WindowManagerState.ConfigurationContainer {
        protected boolean mFullscreen;
        protected Rect mBounds;
        protected int mMinWidth = -1;
        protected int mMinHeight = -1;

        ActivityContainer(ConfigurationContainerProto proto) {
            super(proto);
        }

        public Rect getBounds() {
            return mBounds;
        }

        boolean isFullscreen() {
            return mFullscreen;
        }

        int getMinWidth() {
            return mMinWidth;
        }

        int getMinHeight() {
            return mMinHeight;
        }
    }

    static class KeyguardControllerState {

        boolean aodShowing = false;
        boolean keyguardShowing = false;
        SparseArray<Boolean> mKeyguardOccludedStates = new SparseArray<>();

        KeyguardControllerState(KeyguardControllerProto proto) {
            if (proto != null) {
                aodShowing = proto.aodShowing;
                keyguardShowing = proto.keyguardShowing;
                for (int i = 0;  i < proto.keyguardOccludedStates.length; i++) {
                    mKeyguardOccludedStates.append(proto.keyguardOccludedStates[i].displayId,
                            proto.keyguardOccludedStates[i].keyguardOccluded);
                }
            }
        }

        boolean isKeyguardOccluded(int displayId) {
            if (mKeyguardOccludedStates.get(displayId) != null) {
                return mKeyguardOccludedStates.get(displayId);
            }
            return false;
        }
    }
}
