package io.github.howard20181.hyperos.fcmlive;

import static org.junit.Assert.*;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CapsuleSwitchTest {
    private ActivityController<Activity> controller;
    private CapsuleSwitch toggle;

    @Before public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        toggle = new CapsuleSwitch(controller.get(), null);
        controller.get().setContentView(toggle);
        toggle.measure(View.MeasureSpec.makeMeasureSpec(49, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY));
        toggle.layout(0, 0, 49, 48);
    }

    @After public void tearDown() { controller.pause().stop().destroy(); }

    @Test public void thumbIsSmallerThanTrackAndExactlyMirroredAtBothEnds() throws Exception {
        Bitmap off = draw();
        toggle.setChecked(true);
        toggle.jumpDrawablesToCurrentState();
        Bitmap on = draw();
        assertEquals(Color.WHITE, off.getPixel(14, 24));
        assertEquals(Color.WHITE, on.getPixel(35, 24));
        assertNotEquals(Color.WHITE, off.getPixel(2, 24));
        assertNotEquals(Color.WHITE, on.getPixel(46, 24));
        assertNotEquals(Color.WHITE, off.getPixel(14, 12));
        assertNotEquals(Color.WHITE, on.getPixel(35, 12));
        for (int y = 0; y < 48; y++) {
            for (int x = 0; x < 49; x++) {
                assertEquals("Thumb mirror at " + x + "," + y,
                        off.getPixel(x, y) == Color.WHITE, on.getPixel(48 - x, y) == Color.WHITE);
            }
        }
        toggle.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        Bitmap rtl = draw();
        assertEquals(Color.WHITE, rtl.getPixel(14, 24));
        Bitmap endpoints = Bitmap.createBitmap(196, 384, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(endpoints);
        canvas.drawColor(Color.rgb(240, 240, 240));
        canvas.scale(4, 4);
        canvas.drawBitmap(off, 0, 0, null);
        canvas.drawBitmap(on, 0, 48, null);
        File file = new File("build/reports/ui/switch-endpoints.png");
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        try (FileOutputStream output = new FileOutputStream(file)) {
            endpoints.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        off.recycle(); on.recycle(); rtl.recycle(); endpoints.recycle();
    }

    @Test public void rapidReversalStartsAtCurrentPositionAndFinishesAtEndpoint() throws Exception {
        toggle.setChecked(true);
        ValueAnimator forward = animator();
        assertNotNull(forward);
        forward.setCurrentPlayTime(40);
        float partial = position();
        assertTrue(partial > 0 && partial < 1);
        toggle.setChecked(false);
        ValueAnimator reverse = animator();
        assertNotSame(forward, reverse);
        assertEquals(partial, position(), 0.001f);
        reverse.end();
        assertEquals(0f, position(), 0f);
        toggle.setChecked(true);
        animator().end();
        assertEquals(1f, position(), 0f);
        assertTrue(toggle.createAccessibilityNodeInfo().isChecked());
        assertEquals("android.widget.Switch", toggle.getAccessibilityClassName());
    }

    private Bitmap draw() {
        Bitmap bitmap = Bitmap.createBitmap(49, 48, Bitmap.Config.ARGB_8888);
        toggle.draw(new Canvas(bitmap));
        return bitmap;
    }

    private ValueAnimator animator() throws Exception {
        Field field = CapsuleSwitch.class.getDeclaredField("animator");
        field.setAccessible(true);
        return (ValueAnimator) field.get(toggle);
    }

    private float position() throws Exception {
        Field field = CapsuleSwitch.class.getDeclaredField("position");
        field.setAccessible(true);
        return field.getFloat(toggle);
    }
}
