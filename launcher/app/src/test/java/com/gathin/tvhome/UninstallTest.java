package com.gathin.tvhome;

import android.content.pm.ApplicationInfo;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for which apps the tile menu and the App library offer to uninstall. */
public class UninstallTest {
    @Test public void anAppFromPlayStoreIsOffered() {
        assertTrue(Uninstall.allowed("com.example.player", 0));
        assertTrue(Uninstall.allowed("com.example.player",
                ApplicationInfo.FLAG_HAS_CODE | ApplicationInfo.FLAG_ALLOW_BACKUP));
    }

    @Test public void theTvsOwnAppsAreNot() {
        assertFalse(Uninstall.allowed("com.google.android.youtube.tv", ApplicationInfo.FLAG_SYSTEM));
    }

    @Test public void norIsOneThatPlayStoreHasUpdated() {
        assertFalse(Uninstall.allowed("com.netflix.ninja",
                ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP));
    }

    @Test public void neitherHomeIsEvenWhenSideloaded() {
        assertFalse(Uninstall.allowed("com.gathin.tvhome", 0));
        assertFalse(Uninstall.allowed("tv.afnon.home", 0));
    }

    @Test public void noPackageIsNot() {
        assertFalse(Uninstall.allowed(null, 0));
    }
}
