//Copyright (c) 2015, David Larochelle-Pratte
//All rights reserved.
//
//        Redistribution and use in source and binary forms, with or without
//        modification, are permitted provided that the following conditions are met:
//
//        1. Redistributions of source code must retain the above copyright notice, this
//        list of conditions and the following disclaimer.
//        2. Redistributions in binary form must reproduce the above copyright notice,
//        this list of conditions and the following disclaimer in the documentation
//        and/or other materials provided with the distribution.
//
//        THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
//        ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
//        WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
//        DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
//        ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
//        (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
//        LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
//        ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
//        (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
//        SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
package com.hancinworld.fw.proxy;

import com.hancinworld.fw.handler.ConfigurationHandler;
import com.hancinworld.fw.handler.DrawScreenEventHandler;
import com.hancinworld.fw.handler.KeyInputEventHandler;
import com.hancinworld.fw.reference.Reference;
import com.hancinworld.fw.utility.LogHelper;
import com.hancinworld.fw.utility.SdlWindowHelper;
import net.minecraftforge.common.MinecraftForge;
import cpw.mods.fml.client.SplashProgress;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.relauncher.ReflectionHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.LWJGLException;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.io.File;
import java.lang.reflect.Method;

public class ClientProxy extends CommonProxy {

    private Rectangle _savedWindowedBounds;
    public static boolean currentState;
    public static KeyBinding fullscreenKeyBinding;
    public DrawScreenEventHandler dsHandler;
    private boolean _startupRequestedSetting;

    /** Cached reflection handle to Minecraft.resize(int, int) (func_71370_a). */
    private static Method _resizeMethod;

    /** Lazily evaluated: is lwjgl3ify (GTNH on Java 17+) replacing LWJGL2? */
    private static Boolean _lwjgl3ifyPresent;

    /** This keybind replaces the default MC fullscreen keybind in their logic handler. Without it, the game crashes.
     *  If this is set to any valid key, problems may occur. */
    public static KeyBinding ignoreKeyBinding = new KeyBinding("key.fullscreenwindowed.unused", Keyboard.KEY_NONE, "key.categories.misc");


    public ClientProxy()
    {
        Minecraft mc = Minecraft.getMinecraft();
        _startupRequestedSetting = mc.gameSettings.fullScreen;
        mc.gameSettings.fullScreen = false;
    }

    // ------------------------------------------------------------------------------------------------------------
    // lwjgl3ify (GregTech: New Horizons on Java 17+)
    // ------------------------------------------------------------------------------------------------------------

    /**
     * GTNH can run on Java 17+ through lwjgl3ify, which replaces LWJGL2 with SDL3. The LWJGL2 "undecorated" trick
     * does not exist there, so the borderless window is built through SDL directly (see SdlWindowHelper).
     */
    public static boolean isLwjgl3ifyPresent()
    {
        if(_lwjgl3ifyPresent == null) {
            boolean present;
            try {
                Class.forName("me.eigenraven.lwjgl3ify.core.Config", false, ClientProxy.class.getClassLoader());
                present = true;
            } catch (Throwable t) {
                present = false;
            }
            _lwjgl3ifyPresent = present;
        }
        return _lwjgl3ifyPresent;
    }

    // ------------------------------------------------------------------------------------------------------------

    @Override
    public void registerKeyBindings()
    {
        /* FIXME: Overrides the minecraft hotkey for fullscreen, as there are no hooks */
        if(fullscreenKeyBinding == null && ConfigurationHandler.instance().isFullscreenWindowedEnabled())
        {
            Minecraft mc = Minecraft.getMinecraft();
            fullscreenKeyBinding = mc.gameSettings.field_152395_am;
            mc.gameSettings.field_152395_am = ignoreKeyBinding;

            if(Display.isFullscreen()){
                toggleFullScreen(true, Reference.AUTOMATIC_MONITOR_SELECTION);
            }
        }
        else if(fullscreenKeyBinding != null && !ConfigurationHandler.instance().isFullscreenWindowedEnabled())
        {

            Minecraft mc = Minecraft.getMinecraft();
            mc.gameSettings.field_152395_am = fullscreenKeyBinding;
            fullscreenKeyBinding = null;

            if(currentState){
                //Go back to a normal window first, then let vanilla handle it from now on.
                toggleFullScreen(false, ConfigurationHandler.instance().getFullscreenMonitor());
            }
        }
    }

    @Override
    public void subscribeEvents(File configurationFile) {

        ConfigurationHandler.instance().init(configurationFile);
        FMLCommonHandler.instance().bus().register(ConfigurationHandler.instance());
        FMLCommonHandler.instance().bus().register(new KeyInputEventHandler());
        dsHandler = new DrawScreenEventHandler();
        MinecraftForge.EVENT_BUS.register(dsHandler);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Monitor detection (DPI aware)
    // ------------------------------------------------------------------------------------------------------------

    private static boolean isMac()
    {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("mac");
    }

    /**
     * Returns the bounds of a monitor in PHYSICAL pixels, which is what LWJGL2 (Display.setLocation/setDisplayMode)
     * works with.
     *
     * Java 9+ reports monitor bounds in scaled "logical" pixels when Windows display scaling is active
     * (e.g. a 3840x2160 monitor at 150% reports 2560x1440). That produced a window that only covered part of a
     * 4K screen. We undo the scaling here, and take the real size from the monitor's display mode.
     */
    private static Rectangle getPhysicalBounds(GraphicsDevice dev)
    {
        GraphicsConfiguration config = dev.getDefaultConfiguration();
        Rectangle logical = config.getBounds();

        double scaleX = 1.0, scaleY = 1.0;
        try {
            AffineTransform tx = config.getDefaultTransform();
            if(tx != null) {
                if(tx.getScaleX() > 0) scaleX = tx.getScaleX();
                if(tx.getScaleY() > 0) scaleY = tx.getScaleY();
            }
        } catch (Throwable ignored) {}

        int x = (int) Math.round(logical.x * scaleX);
        int y = (int) Math.round(logical.y * scaleY);
        int w = (int) Math.round(logical.width * scaleX);
        int h = (int) Math.round(logical.height * scaleY);

        //On Windows/Linux the display mode is always the real resolution of the monitor. (On macOS LWJGL2 works
        //in points, not pixels, so the logical bounds are already correct there.)
        if(!isMac()) {
            try {
                java.awt.DisplayMode dm = dev.getDisplayMode();
                if(dm != null && dm.getWidth() > 0 && dm.getHeight() > 0) {
                    w = dm.getWidth();
                    h = dm.getHeight();
                }
            } catch (Throwable ignored) {}
        }

        return new Rectangle(x, y, w, h);
    }

    private static GraphicsDevice[] getScreens()
    {
        try {
            if(GraphicsEnvironment.isHeadless())
                return new GraphicsDevice[0];
            GraphicsDevice[] screens = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
            return screens == null ? new GraphicsDevice[0] : screens;
        } catch (Throwable t) {
            LogHelper.warn("Unable to enumerate monitors: " + t);
            return new GraphicsDevice[0];
        }
    }

    /** Primary monitor according to LWJGL, always in physical pixels. */
    private static Rectangle getPrimaryScreenBounds()
    {
        DisplayMode desktop = Display.getDesktopDisplayMode();
        return new Rectangle(0, 0, desktop.getWidth(), desktop.getHeight());
    }

    /**
     * Finds the monitor the window is on. First try the monitor containing the window center; if none does
     * (window partly off-screen), take the monitor with the biggest overlap; last resort is the primary monitor.
     */
    private Rectangle findCurrentScreenDimensionsAndPosition(Rectangle window)
    {
        Point center = new Point(window.x + window.width / 2, window.y + window.height / 2);
        Rectangle bestOverlap = null;
        long bestArea = 0;

        for(GraphicsDevice dev : getScreens())
        {
            Rectangle bounds = getPhysicalBounds(dev);

            if(bounds.contains(center))
                return bounds;

            Rectangle overlap = bounds.intersection(window);
            if(!overlap.isEmpty()) {
                long area = (long) overlap.width * (long) overlap.height;
                if(area > bestArea) {
                    bestArea = area;
                    bestOverlap = bounds;
                }
            }
        }

        if(bestOverlap != null)
            return bestOverlap;

        //if Java isn't able to find a matching screen then use the old LWJGL calcs.
        return getPrimaryScreenBounds();
    }

    private Rectangle findScreenDimensionsByID(int monitorID)
    {
        if(monitorID < 1)
            return null;

        GraphicsDevice[] screens = getScreens();

        if(screens.length < monitorID){
            return null;
        }

        return getPhysicalBounds(screens[monitorID - 1]);
    }

    // ------------------------------------------------------------------------------------------------------------

    /** Calls the Minecraft resize() method so it updates its framebuffer. */
    private void callMinecraftResizeMethod(int w, int h)
    {
        try{
            Minecraft inst = Minecraft.getMinecraft();
            if(_resizeMethod == null) {
                _resizeMethod = ReflectionHelper.findMethod(Minecraft.class, inst, new String[]{"func_71370_a", "resize"}, int.class, int.class);
            }
            if(_resizeMethod != null)
            {
                Display.update();
                _resizeMethod.invoke(inst, Math.max(1, w), Math.max(1, h));
            }

        }catch (Exception e){
            LogHelper.warn("Resize method not found or problem found while calling it. Are you using the correct version of the mod for this version of Minecraft?" + e.toString());
        }
    }

    private Rectangle getAppropriateScreenBounds(Rectangle currentCoordinates, int desiredMonitor)
    {
        Rectangle screenBounds;

        ConfigurationHandler configuration = ConfigurationHandler.instance();
        //First feature mode: Only remove decorations. No need to calculate screen positions, we're not changing size or location.
        if(configuration.areAdvancedFeaturesEnabled() && configuration.isOnlyRemoveDecorations()){
            screenBounds = currentCoordinates;
        }
        //Custom dimensions enabled: follow requested settings if we can work with them.
        else if(configuration.areAdvancedFeaturesEnabled() && configuration.isCustomFullscreenDimensions() && (configuration.getCustomFullscreenDimensionsH() > 256 && configuration.getCustomFullscreenDimensionsW() > 256))
        {
            screenBounds = new Rectangle(configuration.getCustomFullscreenDimensionsX(),configuration.getCustomFullscreenDimensionsY(), configuration.getCustomFullscreenDimensionsW(),configuration.getCustomFullscreenDimensionsH());

            //If you've selected a monitor, then X & Y are offsets - easier to do math.
            if(desiredMonitor > 0) {
                Rectangle actualScreenBounds = findScreenDimensionsByID(desiredMonitor);
                if(actualScreenBounds != null){
                    screenBounds.setLocation(actualScreenBounds.x + screenBounds.x, actualScreenBounds.y + screenBounds.y);
                }
            }
        }
        // No specified monitor for fullscreen -> find the one the window is on right now
        else if(desiredMonitor < 0 || desiredMonitor == Reference.AUTOMATIC_MONITOR_SELECTION) {
            //find which monitor we should be using based on the MC window
            screenBounds = findCurrentScreenDimensionsAndPosition(currentCoordinates);
        // specified monitor for fullscreen -> get dimensions.
        }else{
            screenBounds = findScreenDimensionsByID(desiredMonitor);
            // you've specified a monitor but it doesn't look connected. Revert to automatic mode.
            if(screenBounds == null){
                screenBounds = findCurrentScreenDimensionsAndPosition(currentCoordinates);
            }
        }

        return screenBounds;
    }

    /** Window size to fall back to when there is no saved windowed size: 3/4 of the monitor, centered. */
    private static Rectangle defaultWindowedBounds(Rectangle screen)
    {
        int w = Math.max(854, screen.width * 3 / 4);
        int h = Math.max(480, screen.height * 3 / 4);
        w = Math.min(w, screen.width);
        h = Math.min(h, screen.height);
        return new Rectangle(screen.x + (screen.width - w) / 2, screen.y + (screen.height - h) / 2, w, h);
    }

    @Override
    public void toggleFullScreen(boolean goFullScreen, int desiredMonitor) {

        //lwjgl3ify (Java 17+): build the borderless window through SDL.
        if(isLwjgl3ifyPresent()) {
            toggleFullScreenSdl(goFullScreen, desiredMonitor);
            return;
        }

        boolean wasRealFullscreen = Display.isFullscreen();

        //If we're in actual fullscreen right now, then we need to fix that.
        if(wasRealFullscreen) {
            currentState = true;
            LogHelper.warn("Display is actual fullscreen! Is Minecraft starting with the option set?");
        }

        // if we have nothing to do (we appear to be in the correct mode) and we're not in actual fullscreen ( that's
        // never an acceptable state in this mod ), just quit now.
        if(currentState == goFullScreen && !wasRealFullscreen)
            return;

        //Save our current display parameters
        Rectangle currentCoordinates = new Rectangle(Display.getX(), Display.getY(), Display.getWidth(), Display.getHeight());
        //Only remember the size if it's a real window (in exclusive fullscreen these values are meaningless).
        if(goFullScreen && !wasRealFullscreen && !currentState)
            _savedWindowedBounds = currentCoordinates;

        //Changing this property and causing a Display update will cause LWJGL to add/remove decorations (borderless).
        System.setProperty("org.lwjgl.opengl.Window.undecorated", goFullScreen?"true":"false");

        //Get the fullscreen dimensions for the appropriate screen.
        Rectangle screenBounds = getAppropriateScreenBounds(currentCoordinates, desiredMonitor);

        //This is the new bounds we have to apply.
        Rectangle newBounds = goFullScreen ? screenBounds : _savedWindowedBounds;
        if(newBounds == null || newBounds.width <= 0 || newBounds.height <= 0)
            newBounds = goFullScreen ? screenBounds : defaultWindowedBounds(screenBounds);

        try {
            //Leave exclusive fullscreen FIRST, otherwise the monitor stays in its exclusive video mode.
            if(wasRealFullscreen)
                Display.setFullscreen(false);

            Display.setDisplayMode(new DisplayMode(newBounds.width, newBounds.height));
            Display.setResizable(!goFullScreen);
            Display.setFullscreen(false);

            Display.update();

            Display.setLocation(newBounds.x, newBounds.y);

            callMinecraftResizeMethod(newBounds.width, newBounds.height);

        } catch (LWJGLException e) {
            LogHelper.warn("Could not switch fullscreen windowed mode: " + e);
            e.printStackTrace();
        }

        currentState = goFullScreen;
        afterToggle(goFullScreen);
    }

    /** Things that have to happen after every switch, in both LWJGL2 and lwjgl3ify mode. */
    private void afterToggle(boolean goFullScreen)
    {
        //Vanilla sets Minecraft.fullscreen = true when it started in exclusive fullscreen. While that flag is set it
        //ignores window resizes, so the framebuffer would stay at the old resolution. We are never in exclusive mode.
        try {
            ReflectionHelper.setPrivateValue(Minecraft.class, Minecraft.getMinecraft(), false, "field_71431_Q", "fullscreen");
        } catch (Throwable t) {
            LogHelper.warn("Could not reset Minecraft fullscreen flag: " + t);
        }

        //Remember the choice, so the game starts in the same mode next time.
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if(mc.gameSettings.fullScreen != goFullScreen) {
                mc.gameSettings.fullScreen = goFullScreen;
                mc.gameSettings.saveOptions();
            }
        } catch (Throwable t) {
            LogHelper.warn("Could not save fullscreen setting: " + t);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // lwjgl3ify / SDL3 implementation
    // ------------------------------------------------------------------------------------------------------------

    private static boolean isWindows()
    {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private Rectangle getSdlTargetBounds(long window, int desiredMonitor) throws Exception
    {
        Rectangle screen = null;
        if(desiredMonitor > 0)
            screen = SdlWindowHelper.getDisplayBoundsByIndex(desiredMonitor);
        if(screen == null)
            screen = SdlWindowHelper.getCurrentDisplayBounds(window);
        if(screen == null)
            throw new IllegalStateException("SDL returned no monitor bounds");

        ConfigurationHandler configuration = ConfigurationHandler.instance();
        if(configuration.areAdvancedFeaturesEnabled() && configuration.isCustomFullscreenDimensions() && (configuration.getCustomFullscreenDimensionsH() > 256 && configuration.getCustomFullscreenDimensionsW() > 256))
        {
            Rectangle custom = new Rectangle(configuration.getCustomFullscreenDimensionsX(), configuration.getCustomFullscreenDimensionsY(), configuration.getCustomFullscreenDimensionsW(), configuration.getCustomFullscreenDimensionsH());
            if(desiredMonitor > 0)
                custom.setLocation(screen.x + custom.x, screen.y + custom.y);
            return custom;
        }

        Rectangle target = new Rectangle(screen);
        //Windows treats a window that exactly covers the monitor like exclusive fullscreen (black flicker on alt-tab,
        //nothing can be drawn on top). One extra pixel of height avoids that - it's invisible, below the screen edge.
        if(isWindows())
            target.height += 1;
        return target;
    }

    private void toggleFullScreenSdl(final boolean goFullScreen, final int desiredMonitor)
    {
        final boolean wasRealFullscreen = Display.isFullscreen();
        if(wasRealFullscreen) {
            currentState = true;
            LogHelper.info("Game is in SDL fullscreen, switching to borderless window.");
        }

        if(currentState == goFullScreen && !wasRealFullscreen)
            return;

        final boolean wasBorderless = currentState && !wasRealFullscreen;

        try {
            //Leave SDL's own (exclusive-like) fullscreen first.
            if(wasRealFullscreen)
                Display.setFullscreen(false);
        } catch (Throwable t) {
            LogHelper.warn("Could not leave SDL fullscreen: " + t);
        }

        final boolean[] ok = { true };
        SdlWindowHelper.runOnMainThread(new Runnable() {
            @Override
            public void run() {
                try {
                    long window = SdlWindowHelper.getWindow();

                    if(goFullScreen) {
                        if(!wasRealFullscreen && !wasBorderless)
                            _savedWindowedBounds = SdlWindowHelper.getWindowBounds(window);

                        Rectangle target = getSdlTargetBounds(window, desiredMonitor);
                        SdlWindowHelper.setBordered(window, false);
                        SdlWindowHelper.setResizable(window, false);
                        SdlWindowHelper.setBounds(window, target);
                        LogHelper.info("Borderless window: " + target.width + "x" + target.height + " at " + target.x + "," + target.y);
                    } else {
                        Rectangle screen = SdlWindowHelper.getCurrentDisplayBounds(window);
                        Rectangle restore = _savedWindowedBounds;
                        if(restore == null || restore.width <= 0 || restore.height <= 0)
                            restore = defaultWindowedBounds(screen != null ? screen : new Rectangle(0, 0, 1920, 1080));

                        SdlWindowHelper.setBordered(window, true);
                        SdlWindowHelper.setResizable(window, true);
                        SdlWindowHelper.setBounds(window, restore);
                    }
                    SdlWindowHelper.sync(window);
                } catch (Throwable t) {
                    ok[0] = false;
                    LogHelper.warn("Borderless window through SDL failed: " + t);
                    t.printStackTrace();
                }
            }
        });

        if(!ok[0])
            return;

        currentState = goFullScreen;
        //Minecraft picks up the new window size by itself (Display.wasResized) once its fullscreen flag is off.
        afterToggle(goFullScreen);
    }

    @Override
    @SuppressWarnings("deprecated")
    public void performStartupChecks()
    {
        //If the mod is disabled by configuration, just put back the initial value.
        if(!ConfigurationHandler.instance().isFullscreenWindowedEnabled()) {
            Minecraft.getMinecraft().gameSettings.fullScreen = _startupRequestedSetting;
            return;
        }

        if(ConfigurationHandler.instance().isMaximumCompatibilityEnabled()) {
            dsHandler.setInitialFullscreen(_startupRequestedSetting,  ConfigurationHandler.instance().getFullscreenMonitor());
        // This is the correct way to set fullscreen at launch, but LWJGL limitations means we might crash the game if
        // another mod tries to do a similar Display changing operation. Doesn't help the API says "don't use this"
        }else{
            try {
                //FIXME: Living dangerously here... Is there a better way of doing this?
                SplashProgress.pause();
                toggleFullScreen(_startupRequestedSetting, ConfigurationHandler.instance().getFullscreenMonitor());
                SplashProgress.resume();
            }catch(NoClassDefFoundError e) {
                LogHelper.warn("Error while doing startup checks, are you using an old version of Forge ? " + e);
                toggleFullScreen(_startupRequestedSetting, ConfigurationHandler.instance().getFullscreenMonitor());
            }
        }
    }
}
