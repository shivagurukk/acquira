package com.acquira.service;

import com.acquira.config.TenantContext;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.FileInputStream;
import java.io.InputStream;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;

@Service
public class FileUploadService {

    private final JobLauncher jobLauncher;
    private final Job merchantMasterJob;
    private final Job transactionLoadJob;

    private final String UPLOAD_DIR = "data/uploads/";

    private final com.acquira.service.AuditService auditService;
    private final com.acquira.repository.TenantRepository tenantRepository;
    private final com.acquira.service.ManualIngestionService manualIngestionService;

    public FileUploadService(JobLauncher jobLauncher,
            @Qualifier("merchantMasterJob") Job merchantMasterJob,
            @Qualifier("transactionLoadJob") Job transactionLoadJob,
            com.acquira.service.AuditService auditService,
            com.acquira.repository.TenantRepository tenantRepository,
            com.acquira.service.ManualIngestionService manualIngestionService) {
        this.jobLauncher = jobLauncher;
        this.merchantMasterJob = merchantMasterJob;
        this.transactionLoadJob = transactionLoadJob;
        this.auditService = auditService;
        this.tenantRepository = tenantRepository;
        this.manualIngestionService = manualIngestionService;

        // Ensure upload directory exists
        try {
            Files.createDirectories(Paths.get(UPLOAD_DIR));
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize upload folder", e);
        }
    }

    // A helper for robust reading
    private String getCellValue(Cell cell) {
        if (cell == null)
            return "";
        DataFormatter formatter = new DataFormatter();
        return formatter.formatCellValue(cell);
    }

    // New Helper to resolve Tenant based on Role and File Content
    private Long resolveTargetTenant(String filePath) {
        // 1. Get Current User Logic
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        boolean isSuperAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN"));

        Long sessionTenantId = TenantContext.getCurrentTenant();

        // 2. Extract Entity ID from File (Row 1, Col 0)
        String fileEntityId = null;
        try (InputStream is = new FileInputStream(filePath);
                Workbook workbook = WorkbookFactory.create(is)) {

            Sheet sheet = workbook.getSheetAt(0);
            // Skip Header (row 0), read Row 1
            if (sheet.getLastRowNum() >= 1) {
                Row row = sheet.getRow(1);
                if (row != null) {
                    Cell cell = row.getCell(0);
                    fileEntityId = getCellValue(cell);
                }
            }
        } catch (Exception e) {
            System.err.println("Could not read Entity ID from file: " + e.getMessage());
        }

        if (fileEntityId == null || fileEntityId.trim().isEmpty()) {
            if (sessionTenantId != null)
                return sessionTenantId; // Fallback to session
            throw new RuntimeException("Could not identify Entity/Tenant from file content (Row 2, Cell 1 missing).");
        }
        fileEntityId = fileEntityId.trim();

        // 3. Super Admin Logic
        if (isSuperAdmin) {
            String finalFileEntityId = fileEntityId;
            return tenantRepository.findByBankShortCode(fileEntityId)
                    .map(com.acquira.model.Tenant::getTenantId)
                    .or(() -> tenantRepository.findByInstitutionId(finalFileEntityId)
                            .map(com.acquira.model.Tenant::getTenantId))
                    .orElseThrow(() -> new RuntimeException(
                            "Super Admin Upload: No Tenant found for Entity ID '" + finalFileEntityId
                                    + "' (Checked Short Code & Institution ID)."));
        } else {
            // 4. Regular User Logic
            if (sessionTenantId == null) {
                throw new RuntimeException("No session tenant found for user.");
            }
            Long finalSessionTenantId = sessionTenantId;
            com.acquira.model.Tenant sessionTenant = tenantRepository.findById(sessionTenantId)
                    .orElseThrow(() -> new RuntimeException("Session Tenant not found in DB"));

            boolean match = fileEntityId.equalsIgnoreCase(sessionTenant.getBankShortCode())
                    || fileEntityId.equalsIgnoreCase(sessionTenant.getInstitutionId());

            if (!match) {
                throw new RuntimeException("Permission Denied: You belong to '" + sessionTenant.getBankShortCode()
                        + "' but are trying to upload data for '" + fileEntityId + "'.");
            }

            return sessionTenantId;
        }
    }

    public org.springframework.batch.core.JobExecution processUnifiedFile(MultipartFile file) throws Exception {
        // Save File FIRST so we can read it
        String filePath = saveFile(file);

        // Detect Type
        String detectedType = detectFileType(filePath);

        if ("LEGACY_EXCEL".equals(detectedType)) {
            throw new RuntimeException(
                    "Format Error: You uploaded a Legacy Excel (.xls) file. Please convert it to Modern Excel (.xlsx) for high-performance processing (Rows > 65k).");
        }

        // Resolve Tenant (Auto-switch for Admin, Validate for User)
        Long targetTenantId = resolveTargetTenant(filePath);
        String entityName = tenantRepository.findById(targetTenantId).map(t -> t.getInstitutionId()).orElse("Unknown");

        if ("MERCHANT".equals(detectedType)) {
            auditService.log("BATCH_RUN",
                    String.format("Processing MERCHANT file for Tenant: %s (%d)", entityName, targetTenantId));

            JobParameters jobParameters = new JobParametersBuilder()
                    .addLong("tenantId", targetTenantId)
                    .addString("fullPath", filePath)
                    .addLong("startedAt", System.currentTimeMillis())
                    .toJobParameters();
            return jobLauncher.run(merchantMasterJob, jobParameters);

        } else if ("TRANSACTION".equals(detectedType)) {
            auditService.log("BATCH_RUN",
                    String.format("Processing TRANSACTION file for Tenant: %s (%d)", entityName,
                            targetTenantId));

            JobParameters jobParameters = new JobParametersBuilder()
                    .addLong("tenantId", targetTenantId)
                    .addString("fullPath", filePath)
                    .addLong("startedAt", System.currentTimeMillis())
                    .toJobParameters();
            org.springframework.batch.core.JobExecution execution = jobLauncher.run(transactionLoadJob, jobParameters);

            // Trigger Reporting Update (Synchronous for now, or could be async)
            // Trigger Reporting Update (Multi-Date / Data-Driven)
            try {
                manualIngestionService.processManualUpload(targetTenantId);
            } catch (Exception e) {
                System.err.println("Failed to update Reporting DB: " + e.getMessage());
                // Don't fail the upload just because reporting failed
            }

            return execution;

        } else {
            throw new RuntimeException("Unknown file format.");
        }
    }

    // Kept for backward compatibility but redirecting execution logic could be
    // complex without file save
    // Deprecating direct calls preferring unified.
    public org.springframework.batch.core.JobExecution processMerchantFile(MultipartFile file) throws Exception {
        return processUnifiedFile(file);
    }

    public org.springframework.batch.core.JobExecution processTransactionFile(MultipartFile file, String paymentDate)
            throws Exception {
        return processUnifiedFile(file);
    }

    private String detectFileType(String filePath) {
        try (InputStream is = new FileInputStream(filePath);
                Workbook workbook = WorkbookFactory.create(is)) {

            // Check for Legacy Excel (XLS)
            if (workbook instanceof org.apache.poi.hssf.usermodel.HSSFWorkbook) {
                // Throwing exception here to be caught handling logic or just return special
                // type
                // Returning LEGACY to handle in caller
                return "LEGACY_EXCEL";
            }

            Sheet sheet = workbook.getSheetAt(0);
            // Check header row (row 0)
            if (sheet.getPhysicalNumberOfRows() > 0) {
                Row row = sheet.getRow(0);
                if (row != null) {
                    boolean hasTransactionId = false;
                    boolean hasMerchantMarker = false;

                    for (Cell cell : row) {
                        String header = getCellValue(cell);
                        if (header != null) {
                            String h = header.trim().toLowerCase();
                            if (h.contains("transaction id") || h.contains("txn id") || h.equals("ref number") ||
                                    h.contains("transaction date") || h.contains("payment date") || h.contains("arn") ||
                                    h.contains("rrn") || h.contains("txn currency")) {
                                hasTransactionId = true;
                            }
                            if (h.contains("merchant name") || h.contains("merchant id") || h.equals("mid")) {
                                hasMerchantMarker = true;
                            }
                        }
                    }
                    if (hasTransactionId)
                        return "TRANSACTION";
                    if (hasMerchantMarker)
                        return "MERCHANT";
                }
            }
        } catch (org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException
                | org.apache.poi.poifs.filesystem.OfficeXmlFileException e) {
            System.err.println("File is not a valid Excel file: " + e.getMessage());
            // This happens if it is CSV or Text
        } catch (Exception e) {
            System.err.println("Error detecting file type: " + e.getMessage());
        }
        return "UNKNOWN";
    }

    private String extractPaymentDate(String filePath) {
        // User Requirement: strict reliance on file content (Payment Date column)
        // Do NOT use filename.

        try (InputStream is = new FileInputStream(filePath);
                Workbook workbook = WorkbookFactory.create(is)) {

            Sheet sheet = workbook.getSheetAt(0);
            int paymentDateColIdx = -1;

            // Header Row
            Row headerRow = sheet.getRow(0);
            if (headerRow != null) {
                for (Cell cell : headerRow) {
                    String h = getCellValue(cell);
                    if (h != null && (h.trim().equalsIgnoreCase("Payment Date")
                            || h.trim().equalsIgnoreCase("PaymentDate")
                            || h.trim().equalsIgnoreCase("Date"))) {
                        paymentDateColIdx = cell.getColumnIndex();
                        break;
                    }
                }
            }

            // Data Row
            if (paymentDateColIdx != -1) {
                Row dataRow = sheet.getRow(1);
                if (dataRow != null) {
                    Cell cell = dataRow.getCell(paymentDateColIdx);
                    // Handle Excel Numeric Date
                    if (cell.getCellType() == CellType.NUMERIC
                            && org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                        return cell.getLocalDateTimeCellValue().toLocalDate().toString();
                    }

                    String val = getCellValue(cell);
                    if (val != null && !val.trim().isEmpty()) {
                        try {
                            // Try parsing standard formats
                            return java.time.LocalDate.parse(val.trim()).toString();
                        } catch (Exception e) {
                            // Try other formats? For now, just strict ISO or Excel numeric
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error extracting payment date: " + e.getMessage());
        }
        return null;
    }

    private String saveFile(MultipartFile file) throws IOException {
        String fileName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
        Path path = Paths.get(UPLOAD_DIR + fileName);
        Files.copy(file.getInputStream(), path);
        return path.toAbsolutePath().toString();
    }
}
