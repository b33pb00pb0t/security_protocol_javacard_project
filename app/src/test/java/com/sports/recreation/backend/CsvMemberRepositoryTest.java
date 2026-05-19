package com.sports.recreation.backend;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class CsvMemberRepositoryTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void persistsMemberLifecycleFields() throws Exception {
        File membersFile = temporaryFolder.newFile("members.csv");
        CsvMemberRepository repository = new CsvMemberRepository(membersFile.getAbsolutePath());

        MemberRecord initialized = repository.ensureInitialized("1234", "VIP");
        assertEquals("000004D2", initialized.getMemberId());
        assertEquals("VIP", initialized.getPackageType());

        repository.activate("1234", "20991231", "+34123456789");
        repository.deactivate("1234");

        CsvMemberRepository reloaded = new CsvMemberRepository(membersFile.getAbsolutePath());
        MemberRecord stored = reloaded.find("000004D2");

        assertNotNull(stored);
        assertEquals(MemberRecord.STATUS_INACTIVE, stored.getStatus());
        assertEquals("20991231", stored.getExpiryDate());
        assertEquals("+34123456789", stored.getPhone());
    }
}
