package effortanalyzer.model;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class GroupedResultTest {

    @Test
    void constructorInitializesAllFields() {
        GroupedResult gr = new GroupedResult("report.json", "ServiceA", "Rule42", "HIGH");
        assertEquals("report.json", gr.getJsonFileName());
        assertEquals("ServiceA",    gr.getComponentName());
        assertEquals("Rule42",      gr.getRuleId());
        assertEquals("HIGH",        gr.getSeverity());
        assertEquals(0,             gr.getAffectedFilesCount());
        assertNotNull(gr.getDetailsArray());
        assertEquals(0, gr.getDetailsArray().size());
    }

    @Test
    void addAffectedFileIncrementsCount() {
        GroupedResult gr = new GroupedResult("f.json", "C", "R", "LOW");
        gr.addAffectedFile("com/example/Foo.class");
        gr.addAffectedFile("com/example/Bar.class");
        assertEquals(2, gr.getAffectedFilesCount());
    }

    @Test
    void addAffectedFileDeduplicated() {
        GroupedResult gr = new GroupedResult("f.json", "C", "R", "LOW");
        gr.addAffectedFile("same/File.class");
        gr.addAffectedFile("same/File.class");   // duplicate
        assertEquals(1, gr.getAffectedFilesCount());
    }

    @Test
    void getAffectedFilesIsUnmodifiable() {
        GroupedResult gr = new GroupedResult("f.json", "C", "R", "LOW");
        gr.addAffectedFile("Foo.class");
        assertThrows(UnsupportedOperationException.class, () -> gr.getAffectedFiles().clear());
    }

    @Test
    void generateKeyFormatsWithPipes() {
        String key = GroupedResult.generateKey("app.json", "Kernel", "DeprecatedAPI");
        assertEquals("app.json|Kernel|DeprecatedAPI", key);
    }

    @Test
    void toStringContainsKeyFields() {
        GroupedResult gr = new GroupedResult("test.json", "MyComp", "MyRule", "MEDIUM");
        String s = gr.toString();
        assertTrue(s.contains("MyComp"));
        assertTrue(s.contains("MyRule"));
        assertTrue(s.contains("MEDIUM"));
    }

    @Test
    void detailsArrayStartsEmpty() {
        GroupedResult gr = new GroupedResult("f.json", "C", "R", "LOW");
        assertEquals(0, gr.getDetailsArray().size());
    }

    @Test
    void addAffectedFileIsThreadSafe() throws InterruptedException {
        GroupedResult gr = new GroupedResult("f.json", "C", "R", "LOW");
        int threads = 20;
        int addsPerThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                for (int i = 0; i < addsPerThread; i++) {
                    gr.addAffectedFile("file_" + threadId + "_" + i + ".class");
                }
            });
        }

        latch.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        // All (threads * addsPerThread) distinct filenames should be counted
        assertEquals(threads * addsPerThread, gr.getAffectedFilesCount());
    }
}