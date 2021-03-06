package cc.blynk.server.core.model.widgets.ui.reporting;

import cc.blynk.server.core.dao.ReportingDiskDao;
import cc.blynk.server.core.model.DashBoard;
import cc.blynk.server.core.model.auth.User;
import cc.blynk.server.core.model.device.Device;
import cc.blynk.server.core.model.enums.PinType;
import cc.blynk.server.core.model.widgets.ui.reporting.source.ReportDataStream;
import cc.blynk.server.core.model.widgets.ui.reporting.source.ReportSource;
import cc.blynk.server.notifications.mail.MailWrapper;
import cc.blynk.utils.FileUtils;
import cc.blynk.utils.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

import static cc.blynk.utils.StringUtils.removeUnsupportedChars;
import static cc.blynk.utils.StringUtils.truncate;
import static java.nio.charset.StandardCharsets.UTF_16;

/**
 * The Blynk Project.
 * Created by Dmitriy Dumanskiy.
 * Created on 31/05/2018.
 *
 */
public abstract class BaseReportTask implements Runnable {

    static final Logger log = LogManager.getLogger(BaseReportTask.class);

    public final ReportTaskKey key;

    final Report report;

    private final MailWrapper mailWrapper;

    private final ReportingDiskDao reportingDiskDao;

    private final String downloadUrl;

    private static final Charset REPORT_ENCODING = UTF_16;
    private static final int size = 64 * 1024;

    protected BaseReportTask(User user, int dashId, Report report,
                             MailWrapper mailWrapper, ReportingDiskDao reportingDiskDao,
                             String downloadUrl) {
        this.key = new ReportTaskKey(user, dashId, report.id);
        this.report = report;
        this.mailWrapper = mailWrapper;
        this.reportingDiskDao = reportingDiskDao;
        this.downloadUrl = downloadUrl;
    }

    private static String deviceAndPinFileName(String deviceName, int deviceId, ReportDataStream reportDataStream) {
        return deviceAndPinFileName(deviceName, deviceId, reportDataStream.pinType, reportDataStream.pin);
    }

    private static String deviceAndPinFileName(String deviceName, int deviceId, PinType pinType, byte pin) {
        return deviceName + "_" + deviceId + "_" + pinType.pintTypeChar + pin + ".csv";
    }

    private static String deviceFileName(String deviceName, int deviceId) {
        return deviceName + "_" + deviceId + ".csv";
    }

    @Override
    public void run() {
        try {
            report.lastReportAt = generateReport();
            log.debug(report);
        } catch (Exception e) {
            log.debug("Error generating report {} for {}.", report, key.user.email, e);
        }
    }

    private static String getCSVDeviceName(DashBoard dash, int deviceId) {
        Device device = dash.getDeviceById(deviceId);
        if (device == null) {
            return String.valueOf(deviceId);
        }

        String deviceName = device.name;
        if (deviceName == null || deviceName.isEmpty()) {
            return String.valueOf(deviceId);
        }

        return StringUtils.escapeCSV(deviceName);
    }

    private static String getDeviceName(DashBoard dash, int deviceId) {
        Device device = dash.getDeviceById(deviceId);
        if (device != null) {
            return truncateFileName(device.name);
        }
        return "";
    }

    private static String truncateFileName(String name) {
        if (name == null) {
            return "";
        }

        String truncated = removeUnsupportedChars(name);
        return truncate(truncated, 16);
    }

    private void sendEmail(Path output) throws Exception {
        String durationLabel = report.reportType.getDurationLabel().toLowerCase();
        String subj = "Your " + durationLabel + " " + report.getReportName() + " is ready";
        String gzipDownloadUrl = downloadUrl + output.getFileName();
        String dynamicSection = report.buildDynamicSection();
        mailWrapper.sendReportEmail(report.recipients, subj, gzipDownloadUrl, dynamicSection);
    }

    protected long generateReport() {
        long now = System.currentTimeMillis();

        String date = LocalDate.now(report.tzName).toString();
        Path userCsvFolder = FileUtils.getUserReportDir(
                key.user.email, key.user.appName, key.reportId, date);

        try {
            DashBoard dash = key.user.profile.getDashByIdOrThrow(key.dashId);
            report.lastRunResult = generateReport(userCsvFolder, dash, now);
        } catch (Exception e) {
            report.lastRunResult = ReportResult.ERROR;
            log.error("Error generating report {} for user {}. ", report.id, key.user.email);
            log.error("Error: ", e);
        }

        long newNow = System.currentTimeMillis();

        log.info("Processed report for {}, time {} ms.", key.user.email, newNow - now);
        return newNow;
    }

    private ReportResult generateReport(Path userCsvFolder, DashBoard dash, long now) throws Exception {
        int fetchCount = (int) report.reportType.getFetchCount(report.granularityType);
        long startFrom = now - TimeUnit.DAYS.toMillis(report.reportType.getDuration());
        //truncate second, minute, hour, depending of granularity in order to do not filter first point.
        //https://github.com/blynkkk/blynk-server/issues/1149
        startFrom = (startFrom / report.granularityType.period) * report.granularityType.period;
        Path output = Paths.get(userCsvFolder.toString() + ".zip");

        boolean hasData = generateReport(output, dash, fetchCount, startFrom);
        if (hasData) {
            sendEmail(output);
            return ReportResult.OK;
        }

        log.info("No data for report for user {} and reportId {}.", key.user.email, report.id);
        return ReportResult.NO_DATA;
    }

    private boolean generateReport(Path output, DashBoard dash, int fetchCount, long startFrom) throws Exception {
        //todo for now supporting only some types of output format
        switch (report.reportOutput) {
            case EXCEL_TAB_PER_DEVICE:
            case MERGED_CSV:
                return merged(output, dash, fetchCount, startFrom);
            case CSV_FILE_PER_DEVICE:
                return filePerDevice(output, dash, fetchCount, startFrom);
            case CSV_FILE_PER_DEVICE_PER_PIN:
            default:
                return filePerDevicePerPin(output, dash, fetchCount, startFrom);
        }
    }

    private boolean merged(Path output, DashBoard dash, int fetchCount, long startFrom) throws Exception {
        boolean atLeastOne = false;
        try (ZipOutputStream zipStream = new ZipOutputStream(Files.newOutputStream(output));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(zipStream, REPORT_ENCODING), size)) {
            String fileName = truncateFileName(report.getReportName()) + ".csv";
            ZipEntry zipEntry = new ZipEntry(fileName);
            zipStream.putNextEntry(zipEntry);
            for (ReportSource reportSource : report.reportSources) {
                if (reportSource.isValid()) {
                    for (int deviceId : reportSource.getDeviceIds()) {
                        String deviceName = getCSVDeviceName(dash, deviceId);
                        for (ReportDataStream reportDataStream : reportSource.reportDataStreams) {
                            if (reportDataStream.isValid()) {
                                ByteBuffer onePinData = reportingDiskDao.getByteBufferFromDisk(key.user,
                                        key.dashId, deviceId, reportDataStream.pinType,
                                        reportDataStream.pin, fetchCount, report.granularityType, 0);

                                if (onePinData != null) {
                                    String pin = reportDataStream.formatPin();
                                    atLeastOne = FileUtils.writeBufToCsvFilterAndFormat(writer,
                                            onePinData, pin, deviceName, startFrom, report.makeFormatter());
                                }
                            }
                        }
                    }
                }
            }
            zipStream.closeEntry();
        }
        return atLeastOne;
    }

    private boolean filePerDevice(Path output, DashBoard dash, int fetchCount, long startFrom) throws Exception {
        boolean atLeastOne = false;
        try (ZipOutputStream zipStream = new ZipOutputStream(Files.newOutputStream(output));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(zipStream, REPORT_ENCODING), size)) {
            for (ReportSource reportSource : report.reportSources) {
                if (reportSource.isValid()) {
                    for (int deviceId : reportSource.getDeviceIds()) {
                        String deviceName = getDeviceName(dash, deviceId);
                        String deviceFileName = deviceFileName(deviceName, deviceId);
                        ZipEntry zipEntry = new ZipEntry(deviceFileName);
                        zipStream.putNextEntry(zipEntry);
                        for (ReportDataStream reportDataStream : reportSource.reportDataStreams) {
                            if (reportDataStream.isValid()) {
                                ByteBuffer onePinData = reportingDiskDao.getByteBufferFromDisk(key.user,
                                        key.dashId, deviceId, reportDataStream.pinType,
                                        reportDataStream.pin, fetchCount, report.granularityType, 0);

                                if (onePinData != null) {
                                    String pin = reportDataStream.formatPin();
                                    atLeastOne = FileUtils.writeBufToCsvFilterAndFormat(writer,
                                            onePinData, pin, startFrom, report.makeFormatter());
                                }
                            }
                        }
                        zipStream.closeEntry();
                    }
                }
            }
        }
        return atLeastOne;
    }

    private boolean filePerDevicePerPin(Path output, DashBoard dash, int fetchCount, long startFrom) throws Exception {
        boolean atLeastOne = false;
        try (ZipOutputStream zipStream = new ZipOutputStream(Files.newOutputStream(output))) {
            for (ReportSource reportSource : report.reportSources) {
                if (reportSource.isValid()) {
                    for (int deviceId : reportSource.getDeviceIds()) {
                        String deviceName = getDeviceName(dash, deviceId);
                        for (ReportDataStream reportDataStream : reportSource.reportDataStreams) {
                            if (reportDataStream.isValid()) {
                                ByteBuffer onePinData = reportingDiskDao.getByteBufferFromDisk(key.user,
                                        key.dashId, deviceId, reportDataStream.pinType,
                                        reportDataStream.pin, fetchCount, report.granularityType, 0);

                                if (onePinData != null) {
                                    String onePinDataCsv = FileUtils.writeBufToCsvFilterAndFormat(onePinData,
                                            startFrom, report.makeFormatter());
                                    if (onePinDataCsv.length() > 0) {
                                        String onePinFileName =
                                                deviceAndPinFileName(deviceName, deviceId, reportDataStream);
                                        addZipEntryAndWrite(zipStream, onePinFileName,
                                                onePinDataCsv.getBytes(REPORT_ENCODING));
                                        atLeastOne = true;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return atLeastOne;
    }

    private void addZipEntryAndWrite(ZipOutputStream zipStream,
                                        String onePinFileName, byte[] onePinDataCsv) throws IOException {
        ZipEntry zipEntry = new ZipEntry(onePinFileName);
        try {
            zipStream.putNextEntry(zipEntry);
            zipStream.write(onePinDataCsv);
            zipStream.closeEntry();
        } catch (ZipException zipException) {
            String message = zipException.getMessage();
            if (message != null && message.contains("duplicate")) {
                log.warn("Duplicate zip entry {}. Wrong report configuration.", onePinFileName);
            } else {
                log.error("Error compressing report file.", message);
                throw zipException;
            }
        } catch (IOException e) {
            log.error("Error compressing report file.", e.getMessage());
            throw e;
        }
    }

}
