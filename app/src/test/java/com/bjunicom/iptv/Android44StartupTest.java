package com.bjunicom.iptv;

import android.app.Dialog;
import android.graphics.drawable.Drawable;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.bjunicom.iptv.player.IjkVideoView;
import com.bjunicom.iptv.ui.MainActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowSurfaceView;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Android 4.4.4 (API 19) 兼容性冒烟测试。
 * 与 StartupSmokeTest 相同的启动路径，但强制运行在 API 19 运行时上，用于发现：
 * 1. setElevation / setBackgroundTintList / MediaCodec.getInputBuffer 等仅 API 21+ 的调用；
 * 2. <ripple> 背景在 API 19 无法解析导致布局 inflate 崩溃；
 * 3. 矢量图 (vector) 作为 launcher icon 在 API 19 无法加载。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19, application = IptvApplication.class)
public class Android44StartupTest {

    @Test
    public void testMainActivityStartupOnApi19() {
        ShadowLog.stream = System.out;
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            activity.onWindowFocusChanged(true);

            IjkVideoView videoView = activity.findViewById(R.id.ijk_video_view);
            SurfaceView surfaceView = (SurfaceView) videoView.getChildAt(0);
            ShadowSurfaceView.FakeSurfaceHolder holder = Shadows.shadowOf(surfaceView).getFakeSurfaceHolder();
            for (SurfaceHolder.Callback cb : holder.getCallbacks()) {
                cb.surfaceCreated(holder);
                cb.surfaceChanged(holder, 1, 1920, 1080);
            }

            // 打开频道抽屉：触发 RecyclerView / 分类标签的 selector 背景与焦点绘制路径
            activity.findViewById(R.id.btn_toggle_drawer).performClick();
            Robolectric.flushForegroundThreadScheduler();
        }
    }

    @Test
    public void testMaterialDialogsInflateOnApi19() {
        ShadowLog.stream = System.out;
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            activity.onWindowFocusChanged(true);

            activity.findViewById(R.id.btn_open_settings).performClick();
            Robolectric.flushForegroundThreadScheduler();
            Dialog settingsDialog = ShadowDialog.getLatestDialog();
            assertNotNull("settings dialog should inflate on API 19", settingsDialog);
            settingsDialog.dismiss();

            activity.findViewById(R.id.btn_numpad_dialog).performClick();
            Robolectric.flushForegroundThreadScheduler();
            Dialog numpadDialog = ShadowDialog.getLatestDialog();
            assertNotNull("numpad dialog should inflate on API 19", numpadDialog);
            numpadDialog.dismiss();
        }
    }

    @Test
    public void testLauncherIconLoadsOnApi19() {
        // API 19 的 Resources 无法解析 <vector>，图标必须是 PNG，否则桌面会显示空白/报错。
        // 这里直接走 Resources 加载（与系统桌面/设置取图标同一路径），并断言拿到的是位图而非矢量图。
        android.content.Context context = androidx.test.core.app.ApplicationProvider.getApplicationContext();
        Drawable icon = context.getResources().getDrawable(R.drawable.ic_launcher);
        assertNotNull("launcher icon must be loadable on API 19", icon);
        assertTrue("API 19 应命中 drawable-*dpi/ic_launcher.png",
                icon instanceof android.graphics.drawable.BitmapDrawable);
    }
}
