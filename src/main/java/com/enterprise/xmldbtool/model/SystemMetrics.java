package com.enterprise.xmldbtool.model;

public class SystemMetrics {
    // JVM details
    private String javaVersion;
    private String jvmVendor;
    private String jvmName;
    private String jvmUptimeFormatted;
    private long heapUsedBytes;
    private long heapMaxBytes;
    private long heapTotalBytes;
    private long heapFreeBytes;
    private String heapUsedFormatted;
    private String heapMaxFormatted;
    private double heapUsagePercent;
    private long gcCount;
    private long gcTimeMs;

    // CPU and OS details
    private int availableProcessors;
    private double systemCpuLoadPercent;
    private double processCpuLoadPercent;
    private double systemLoadAverage;
    private String osName;
    private String osArch;
    private String osVersion;
    private String timestamp;

    public SystemMetrics() {}

    public String getJavaVersion() { return javaVersion; }
    public void setJavaVersion(String javaVersion) { this.javaVersion = javaVersion; }

    public String getJvmVendor() { return jvmVendor; }
    public void setJvmVendor(String jvmVendor) { this.jvmVendor = jvmVendor; }

    public String getJvmName() { return jvmName; }
    public void setJvmName(String jvmName) { this.jvmName = jvmName; }

    public String getJvmUptimeFormatted() { return jvmUptimeFormatted; }
    public void setJvmUptimeFormatted(String jvmUptimeFormatted) { this.jvmUptimeFormatted = jvmUptimeFormatted; }

    public long getHeapUsedBytes() { return heapUsedBytes; }
    public void setHeapUsedBytes(long heapUsedBytes) { this.heapUsedBytes = heapUsedBytes; }

    public long getHeapMaxBytes() { return heapMaxBytes; }
    public void setHeapMaxBytes(long heapMaxBytes) { this.heapMaxBytes = heapMaxBytes; }

    public long getHeapTotalBytes() { return heapTotalBytes; }
    public void setHeapTotalBytes(long heapTotalBytes) { this.heapTotalBytes = heapTotalBytes; }

    public long getHeapFreeBytes() { return heapFreeBytes; }
    public void setHeapFreeBytes(long heapFreeBytes) { this.heapFreeBytes = heapFreeBytes; }

    public String getHeapUsedFormatted() { return heapUsedFormatted; }
    public void setHeapUsedFormatted(String heapUsedFormatted) { this.heapUsedFormatted = heapUsedFormatted; }

    public String getHeapMaxFormatted() { return heapMaxFormatted; }
    public void setHeapMaxFormatted(String heapMaxFormatted) { this.heapMaxFormatted = heapMaxFormatted; }

    public double getHeapUsagePercent() { return heapUsagePercent; }
    public void setHeapUsagePercent(double heapUsagePercent) { this.heapUsagePercent = heapUsagePercent; }

    public long getGcCount() { return gcCount; }
    public void setGcCount(long gcCount) { this.gcCount = gcCount; }

    public long getGcTimeMs() { return gcTimeMs; }
    public void setGcTimeMs(long gcTimeMs) { this.gcTimeMs = gcTimeMs; }

    public int getAvailableProcessors() { return availableProcessors; }
    public void setAvailableProcessors(int availableProcessors) { this.availableProcessors = availableProcessors; }

    public double getSystemCpuLoadPercent() { return systemCpuLoadPercent; }
    public void setSystemCpuLoadPercent(double systemCpuLoadPercent) { this.systemCpuLoadPercent = systemCpuLoadPercent; }

    public double getProcessCpuLoadPercent() { return processCpuLoadPercent; }
    public void setProcessCpuLoadPercent(double processCpuLoadPercent) { this.processCpuLoadPercent = processCpuLoadPercent; }

    public double getSystemLoadAverage() { return systemLoadAverage; }
    public void setSystemLoadAverage(double systemLoadAverage) { this.systemLoadAverage = systemLoadAverage; }

    public String getOsName() { return osName; }
    public void setOsName(String osName) { this.osName = osName; }

    public String getOsArch() { return osArch; }
    public void setOsArch(String osArch) { this.osArch = osArch; }

    public String getOsVersion() { return osVersion; }
    public void setOsVersion(String osVersion) { this.osVersion = osVersion; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
}
