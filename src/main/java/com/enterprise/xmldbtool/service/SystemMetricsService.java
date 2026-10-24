package com.enterprise.xmldbtool.service;

import com.enterprise.xmldbtool.model.SystemMetrics;
import org.springframework.stereotype.Service;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class SystemMetricsService {

    public SystemMetrics getSystemMetrics() {
        SystemMetrics metrics = new SystemMetrics();

        // 1. JVM Runtime & Version Details
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        metrics.setJavaVersion(System.getProperty("java.version") + " (" + System.getProperty("java.runtime.version", "") + ")");
        metrics.setJvmVendor(System.getProperty("java.vendor"));
        metrics.setJvmName(runtimeBean.getVmName() + " (" + runtimeBean.getVmVersion() + ")");
        metrics.setJvmUptimeFormatted(formatUptime(runtimeBean.getUptime()));

        // 2. Memory & Heap Details
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memBean.getHeapMemoryUsage();
        long used = heap.getUsed();
        long max = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();
        long total = heap.getCommitted();
        long free = total > used ? total - used : 0;

        metrics.setHeapUsedBytes(used);
        metrics.setHeapMaxBytes(max);
        metrics.setHeapTotalBytes(total);
        metrics.setHeapFreeBytes(free);
        metrics.setHeapUsedFormatted(formatBytes(used));
        metrics.setHeapMaxFormatted(formatBytes(max));
        double percent = max > 0 ? ((double) used / (double) max) * 100.0 : 0.0;
        metrics.setHeapUsagePercent(Math.round(percent * 10.0) / 10.0);

        // 3. Garbage Collection
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        long gcCount = 0;
        long gcTime = 0;
        for (GarbageCollectorMXBean gc : gcBeans) {
            long count = gc.getCollectionCount();
            if (count > 0) gcCount += count;
            long time = gc.getCollectionTime();
            if (time > 0) gcTime += time;
        }
        metrics.setGcCount(gcCount);
        metrics.setGcTimeMs(gcTime);

        // 4. Operating System & CPU Core Details
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        metrics.setAvailableProcessors(osBean.getAvailableProcessors());
        metrics.setOsName(osBean.getName());
        metrics.setOsArch(osBean.getArch());
        metrics.setOsVersion(osBean.getVersion());
        metrics.setSystemLoadAverage(Math.round(osBean.getSystemLoadAverage() * 100.0) / 100.0);

        // Query extended OS bean for real-time CPU usage
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOs) {
            double sysCpu = sunOs.getCpuLoad();
            if (sysCpu < 0) sysCpu = sunOs.getSystemCpuLoad();
            double procCpu = sunOs.getProcessCpuLoad();

            metrics.setSystemCpuLoadPercent(sysCpu >= 0 ? Math.round(sysCpu * 1000.0) / 10.0 : 0.0);
            metrics.setProcessCpuLoadPercent(procCpu >= 0 ? Math.round(procCpu * 1000.0) / 10.0 : 0.0);
        } else {
            metrics.setSystemCpuLoadPercent(0.0);
            metrics.setProcessCpuLoadPercent(0.0);
        }

        metrics.setTimestamp(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return metrics;
    }

    private String formatUptime(long uptimeMs) {
        long sec = uptimeMs / 1000;
        long hours = sec / 3600;
        long mins = (sec % 3600) / 60;
        long s = sec % 60;
        return String.format("%02dh %02dm %02ds", hours, mins, s);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
}
