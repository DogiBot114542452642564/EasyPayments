package ru.soknight.easypayments.task;

import org.bukkit.plugin.Plugin;
import ru.soknight.easypayments.EasyPaymentsPlugin;
import ru.soknight.easypayments.cache.ReportCache;
import ru.soknight.easypayments.sdk.EasyPaymentsSDK;
import ru.soknight.easypayments.sdk.data.model.ProcessPaymentReport;
import ru.soknight.easypayments.sdk.data.model.ProcessPaymentReports;
import ru.soknight.easypayments.sdk.exception.ApiException;
import ru.soknight.easypayments.sdk.exception.ErrorResponseException;
import ru.soknight.easypayments.sdk.exception.UnsuccessfulResponseException;
import ru.soknight.easypayments.sdk.response.AbstractResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class ReportCacheWorker extends AbstractPluginTask {

    private static final long TASK_PERIOD = 6000L;

    private final EasyPaymentsSDK sdk;
    private final ReportCache reportCache;

    public ReportCacheWorker(Plugin plugin, EasyPaymentsSDK sdk, ReportCache reportCache) {
        super(plugin, 20L);

        this.sdk = sdk;
        this.reportCache = reportCache;
    }

    @Override
    protected long getPeriod() {
        return TASK_PERIOD;
    }

    @Override
    public void run() {
        synchronized (sdk) {
            List<ProcessPaymentReport> cachedReports = reportCache.getCachedReports();
            if(cachedReports.isEmpty())
                return;

            int maxFailedAttempts = plugin.getConfig().getInt("max-failed-attempts", 10);
            if(maxFailedAttempts < 1) {
                reportCache.clear();
                return;
            }

            ProcessPaymentReports reports = ProcessPaymentReports.create(cachedReports);
            try {
                Map<Integer, Boolean> processedReports = sdk.reportProcessPayments(reports);
                handleResponse(processedReports, cachedReports);
            } catch (ApiException ex) {
                if(EasyPaymentsPlugin.logCacheWorkerErrors())
                    error("API error: " + ex.getMessage());
            } catch (UnsuccessfulResponseException ex) {
                AbstractResponse<Map<Integer, Boolean>> response = ex.getResponse();
                Map<Integer, Boolean> processedReports = response.getResponseObject();
                handleResponse(processedReports, cachedReports);
            } catch (ErrorResponseException ex) {
                if(EasyPaymentsPlugin.logCacheWorkerErrors()) {
                    error("[CacheWorker] HTTP request failed!");
                    error("[CacheWorker] Details: " + ex.getMessage());
                }
            } catch (IOException ex) {
                if(EasyPaymentsPlugin.logCacheWorkerErrors()) {
                    error("[CacheWorker] Cannot connect to the API server!");
                    error("[CacheWorker] Details: " + ex.getMessage());
                }
            }

            cachedReports = reportCache.getCachedReports();
            List<ProcessPaymentReport> removedReports = new ArrayList<>();
            cachedReports.removeIf(report -> filterCachedReport(report, maxFailedAttempts, removedReports));
            reportCache.unloadReports(removedReports);
            alertAboutRemovedReports(removedReports);
        }
    }

    private void handleResponse(Map<Integer, Boolean> processed, List<ProcessPaymentReport> cachedReports) {
        int preSize = cachedReports.size();

        List<ProcessPaymentReport> uploadedReports = new ArrayList<>();
        cachedReports.removeIf(report -> filterProcessedReport(report, processed, uploadedReports));
        int postSize = cachedReports.size();

        int uploaded = uploadedReports.size();
        if(uploaded != 0 && EasyPaymentsPlugin.isDebugEnabled())
            plugin.getLogger().info(String.format(
                    "[CacheWorker] %d of %d report(s) has been uploaded successfully.",
                    uploaded, preSize
            ));

        if(postSize != 0 && EasyPaymentsPlugin.logCacheWorkerWarnings())
            plugin.getLogger().warning(String.format(
                    "[CacheWorker] %d of %d report(s) weren't uploaded! Retrying in 5 minites...",
                    postSize, preSize
            ));

        reportCache.unloadReports(uploadedReports);
        cachedReports.forEach(ProcessPaymentReport::increaseFailedAttemptsCounter);
    }

    private boolean filterProcessedReport(
            ProcessPaymentReport report,
            Map<Integer, Boolean> processedReports,
            List<ProcessPaymentReport> removedReports
    ) {
        if(processedReports.getOrDefault(report.getPaymentId(), false)) {
            removedReports.add(report);
            return true;
        } else
            return false;
    }

    private boolean filterCachedReport(
            ProcessPaymentReport report,
            int maxFailedAttempts,
            List<ProcessPaymentReport> removedReports
    ) {
        if(report.getFailedAttempts() >= maxFailedAttempts) {
            removedReports.add(report);
            return true;
        } else
            return false;
    }

    private void alertAboutRemovedReports(List<ProcessPaymentReport> removedReports) {
        if(removedReports.isEmpty())
            return;

        String ids = removedReports.stream()
                .map(ProcessPaymentReport::getPaymentId)
                .map(String::valueOf)
                .collect(Collectors.joining(", "));

        if(EasyPaymentsPlugin.logCacheWorkerErrors()) {
            error("The cache worker couldn't upload cached reports!");
            error("All payments with next IDs weren't reported:");
            error(ids);
        }
    }

    private void error(String format, Object... args) {
        plugin.getLogger().severe(String.format(format, args));
    }

}
