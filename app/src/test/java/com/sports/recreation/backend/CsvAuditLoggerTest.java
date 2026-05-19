package com.sports.recreation.backend;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class CsvAuditLoggerTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void writesAndReadsAuditEvents() throws Exception {
        File auditFile = temporaryFolder.newFile("audit_log.csv");
        CsvAuditLogger logger = new CsvAuditLogger(auditFile.getAbsolutePath());

        logger.log("ACCESS", "1234", "CHECK_IN_T1", false, "ACCESS DENIED: blocked, needs review");

        List<AuditEvent> events = logger.readAll();
        assertEquals(1, events.size());
        assertEquals("ACCESS", events.get(0).getTerminal());
        assertEquals("000004D2", events.get(0).getMemberId());
        assertEquals("CHECK_IN_T1", events.get(0).getAction());
        assertEquals("DENIED", events.get(0).getResult());
        assertEquals("ACCESS DENIED: blocked; needs review", events.get(0).getMessage());
    }
}
