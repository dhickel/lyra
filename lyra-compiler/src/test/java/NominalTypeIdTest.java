import io.mindspice.lyra.compiler.identity.ModuleIdentity;
import io.mindspice.lyra.compiler.identity.NominalTypeId;
import io.mindspice.lyra.compiler.source.ModuleId;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** Identity-only tests; these are not nominal source execution coverage. */
public class NominalTypeIdTest {
    private static final String REVISION = "abcdef0123456789".repeat(4);
    private static final ModuleIdentity MODULE = ModuleIdentity.of(ModuleId.of("model.lyra"));

    @Test
    void eachDeclarationComponentParticipatesInIdentity() {
        NominalTypeId original = new NominalTypeId(MODULE, REVISION, "Counter", 0);
        List<NominalTypeId> distinct = List.of(
                original,
                new NominalTypeId(ModuleIdentity.of(ModuleId.of("other.lyra")), REVISION, "Counter", 0),
                new NominalTypeId(MODULE, "0".repeat(64), "Counter", 0),
                new NominalTypeId(MODULE, REVISION, "Other", 0),
                new NominalTypeId(MODULE, REVISION, "Counter", 1),
                new NominalTypeId(MODULE, REVISION, "Counter", Long.MAX_VALUE));
        assertEquals(distinct.size(), distinct.stream().distinct().count());
        assertEquals(distinct.size(), distinct.stream().map(NominalTypeId::stableHash).distinct().count());
        assertEquals(distinct.size(), new TreeSet<>(distinct).size());
        assertEquals(original, new NominalTypeId(MODULE, REVISION, "Counter", 0));
        assertEquals(original.hashCode(), new NominalTypeId(MODULE, REVISION, "Counter", 0).hashCode());
    }

    @Test
    void revisionHexCaseNormalizesWithoutLocaleDependence() {
        NominalTypeId lower = new NominalTypeId(MODULE, REVISION, "Identity", 0);
        NominalTypeId upper = new NominalTypeId(MODULE, REVISION.toUpperCase(Locale.ROOT), "Identity", 0);
        assertEquals(lower, upper);
        assertEquals(lower.hashCode(), upper.hashCode());
        assertEquals(0, lower.compareTo(upper));
        assertEquals(lower.canonicalInput(), upper.canonicalInput());
        assertEquals(lower.stableHash(), upper.stableHash());
        assertEquals(REVISION, upper.revision());
    }

    @Test
    void canonicalEncodingHasIndependentFixedVector() throws Exception {
        NominalTypeId id = new NominalTypeId(MODULE, REVISION, "Counter", 12);
        String expected = "LYRA-NOMINAL-TYPE-ID/1;15:path:model.lyra"
                + "64:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
                + "7:Counter2:12";
        // path:model.lyra is 15 UTF-8 bytes; lengths disambiguate embedded punctuation.
        assertEquals(expected, id.canonicalInput());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(expected.getBytes(StandardCharsets.UTF_8))), id.stableHash());
    }

    @Test
    void rejectsMalformedIdentityInputs() {
        for (String name : List.of("", "counter", "_Counter", "9Counter", "Counter-name",
                "Counter.Name", "Counter ", " Counter", "Cøunter", "Counter\n", "Class<T>")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new NominalTypeId(MODULE, REVISION, name, 0), name);
        }
        for (String revision : List.of("", "a".repeat(63), "a".repeat(65), "g".repeat(64),
                " " + "a".repeat(63), "a".repeat(63) + "\n")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new NominalTypeId(MODULE, revision, "Counter", 0));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new NominalTypeId(MODULE, REVISION, "Counter", -1));
        assertThrows(NullPointerException.class, () -> new NominalTypeId(null, REVISION, "Counter", 0));
        assertThrows(NullPointerException.class, () -> new NominalTypeId(MODULE, null, "Counter", 0));
        assertThrows(NullPointerException.class, () -> new NominalTypeId(MODULE, REVISION, null, 0));
        assertThrows(NullPointerException.class,
                () -> new NominalTypeId(MODULE, REVISION, "Counter", 0).compareTo(null));
    }

    @Test
    void moduleEncodingPreservesUnicodeAndUriKind() {
        NominalTypeId unicode = new NominalTypeId(
                ModuleIdentity.of(ModuleId.path("café.lyra")), REVISION, "Counter", 0);
        assertTrue(unicode.canonicalInput().startsWith("LYRA-NOMINAL-TYPE-ID/1;15:path:café.lyra"));
        NominalTypeId uri = new NominalTypeId(
                ModuleIdentity.of(ModuleId.uri(URI.create("memory:model;v1"))), REVISION, "Counter", 0);
        assertTrue(uri.canonicalInput().startsWith("LYRA-NOMINAL-TYPE-ID/1;19:uri:memory:model;v1"));
        assertNotEquals(unicode, uri);
        assertNotEquals(unicode.stableHash(), uri.stableHash());
    }

    @Test
    void seededIdentityModelPreservesEqualityAndOrdering() {
        for (long seed : new long[] {1, 24301, 8675309, Long.MAX_VALUE}) {
            Random random = new Random(seed);
            HashMap<List<Object>, NominalTypeId> model = new HashMap<>();
            HashMap<String, List<Object>> canonicalOwners = new HashMap<>();
            HashMap<String, List<Object>> hashOwners = new HashMap<>();
            TreeSet<NominalTypeId> ordered = new TreeSet<>();
            for (int index = 0; index < 2048; index++) {
                String path = "m" + random.nextInt(8) + ".lyra";
                String revision = Integer.toHexString(random.nextInt(4)).repeat(64);
                String name = "Type" + random.nextInt(8);
                long occurrence = random.nextInt(4);
                List<Object> key = List.of(path, revision, name, occurrence);
                NominalTypeId id = new NominalTypeId(ModuleIdentity.of(ModuleId.of(path)),
                        revision, name, occurrence);
                NominalTypeId previous = model.putIfAbsent(key, id);
                if (previous != null) {
                    assertEquals(previous, id);
                    assertEquals(previous.hashCode(), id.hashCode());
                    assertEquals(0, previous.compareTo(id));
                }
                List<Object> canonicalOwner = canonicalOwners.putIfAbsent(id.canonicalInput(), key);
                List<Object> hashOwner = hashOwners.putIfAbsent(id.stableHash(), key);
                if (canonicalOwner != null) assertEquals(key, canonicalOwner);
                if (hashOwner != null) assertEquals(key, hashOwner);
                ordered.add(id);
            }
            assertEquals(model.size(), canonicalOwners.size(), "seed=" + seed);
            assertEquals(model.size(), hashOwners.size(), "seed=" + seed);
            assertEquals(model.size(), ordered.size(), "seed=" + seed);
        }
    }
}
