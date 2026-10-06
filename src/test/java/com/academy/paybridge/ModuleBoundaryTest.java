package com.academy.paybridge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforces the README rule: "A module may only use another module through its api/ package."
 * It reads the import lines of every main source file, so a violation fails the build.
 */
class ModuleBoundaryTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/academy/paybridge");
    private static final Set<String> MODULES = Set.of(
            "customer", "account", "ledger", "transfer", "fx", "remittance", "settlement", "compliance", "notification");
    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?com\\.academy\\.paybridge\\.(\\w+)\\.(\\w+)[.;\\w]*");

    @Test
    void modulesOnlyImportOtherModulesThroughTheirApiPackage() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String module : MODULES) {
            for (Path file : javaFiles(SOURCE_ROOT.resolve(module))) {
                for (String line : Files.readAllLines(file)) {
                    Matcher m = IMPORT.matcher(line.trim());
                    if (!m.find()) {
                        continue;
                    }
                    String targetModule = m.group(1);
                    String targetPackage = m.group(2);
                    boolean otherModule = MODULES.contains(targetModule) && !targetModule.equals(module);
                    if (otherModule && !targetPackage.equals("api")) {
                        violations.add(file + " -> " + line.trim());
                    }
                }
            }
        }
        assertThat(violations).as("cross-module imports that bypass api/").isEmpty();
    }

    @Test
    void sharedModuleDoesNotDependOnFeatureModules() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(SOURCE_ROOT.resolve("shared"))) {
            for (String line : Files.readAllLines(file)) {
                Matcher m = IMPORT.matcher(line.trim());
                if (m.find() && MODULES.contains(m.group(1))) {
                    violations.add(file + " -> " + line.trim());
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void serviceAndWebLayersDoNotKnowWhichProviderIsUsed() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String layer : new String[] {"service", "web", "domain", "repository", "api"}) {
            for (Path file : javaFiles(SOURCE_ROOT.resolve("transfer").resolve(layer))) {
                for (String line : Files.readAllLines(file)) {
                    if (line.trim().startsWith("import ") && (line.contains(".client.paystack") || line.contains(".client.simulated"))) {
                        violations.add(file + " -> " + line.trim());
                    }
                }
            }
        }
        assertThat(violations).as("only the TransferGateway interface may be used outside transfer/client").isEmpty();
    }

    private static List<Path> javaFiles(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }
}
