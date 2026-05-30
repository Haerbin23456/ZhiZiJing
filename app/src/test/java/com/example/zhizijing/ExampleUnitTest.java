package com.example.zhizijing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.zhizijing.ui.main.FeatureStatus;
import com.example.zhizijing.ui.main.ProjectStatus;
import org.junit.Test;

public class ExampleUnitTest {
    @Test
    public void projectStatusMarksUnimplementedFeaturesAsPending() {
        assertEquals("智姿镜", ProjectStatus.appTitle);
        assertTrue(ProjectStatus.INSTANCE.getFeatureStatuses().stream()
                .anyMatch(status -> "已修复".equals(status.getState())));
        assertTrue(ProjectStatus.INSTANCE.getFeatureStatuses().stream()
                .map(FeatureStatus::getState)
                .anyMatch(ProjectStatus.pendingHint::equals));
        assertFalse(ProjectStatus.INSTANCE.getFeatureStatuses().stream()
                .map(FeatureStatus::getDetail)
                .anyMatch(detail -> detail.contains("839 204")));
    }
}
