package io.mindspice.lyra.runtime;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Isolated class-initialization order regression for primitive constant ownership.
 *
 * <p>Primitive constants are owned by {@link PrimitiveType}. {@link LyraType} declares no static
 * constant fields, because such a field is unsafe: JLS 12.4.2 initializes a class's
 * superinterfaces that declare at least one default method before the class, so an interface with
 * a default method is initialized while the enum's constants are still null when initialization
 * begins with that enum. A test that only loads the already-initialized application copies would
 * never observe that, which is why both initialization orders run in a fresh isolated loader.</p>
 *
 * <p>Each order runs in an isolated {@link URLClassLoader} whose only parent is the platform class
 * loader. Parent-first delegation is still attempted, but that parent cannot resolve project types,
 * so the JVM cannot satisfy {@code LyraType} or {@code PrimitiveType} and reuse an already
 * initialized application-loader copy. The test therefore exercises the real initialization order
 * instead of the application test class loader's order.</p>
 */
public final class PrimitiveTypeInitializationTest {
    private static final String LYRA_TYPE = "io.mindspice.lyra.runtime.LyraType";
    private static final String PRIMITIVE_TYPE = "io.mindspice.lyra.runtime.PrimitiveType";
    private static final String TYPE_QUALIFIER = "io.mindspice.lyra.runtime.TypeQualifier";
    private static final List<String> CONSTANT_NAMES = List.of(
            "I8", "I16", "I32", "I64", "U8", "U16", "U32", "U64", "F32", "F64", "BOOL", "CHAR",
            "STRING", "UNIT");
    private static final List<String> SOURCE_SPELLINGS = List.of(
            "I8", "I16", "I32", "I64", "U8", "U16", "U32", "U64", "F32", "F64", "Bool", "Char",
            "String", "Unit");
    private static final List<String> REMOVED_ALIASES = List.of(
            "I8", "I16", "I32", "I64", "U8", "U16", "U32", "U64", "F32", "F64", "BOOL", "CHAR",
            "STRING", "UNIT", "Bool", "Char", "String", "Unit");

    @Test
    void lyraTypeInitializedFirstExposesEveryPrimitive() throws Exception {
        assertPrimitiveUniverseAfterInitializingFirst(LYRA_TYPE);
    }

    @Test
    void primitiveTypeInitializedFirstExposesEveryPrimitive() throws Exception {
        assertPrimitiveUniverseAfterInitializingFirst(PRIMITIVE_TYPE);
    }

    @Test
    void primitiveNameTableMatchesTheEnumConstantOrder() {
        List<PrimitiveType> declared = List.of(PrimitiveType.values());
        assertEquals(CONSTANT_NAMES, declared.stream().map(Enum::name).toList());
        assertEquals(SOURCE_SPELLINGS, declared.stream().map(PrimitiveType::spelling).toList());
    }

    private static void assertPrimitiveUniverseAfterInitializingFirst(String firstInitialized)
            throws Exception {
        try (URLClassLoader loader = isolatedLoader()) {
            Class<?> firstClass = Class.forName(firstInitialized, true, loader);
            assertSame(loader, firstClass.getClassLoader(),
                    "the first type must be defined by the isolated loader");
            assertEquals(firstInitialized, firstClass.getName());

            Class<?> lyraTypeClass = Class.forName(LYRA_TYPE, true, loader);
            Class<?> primitiveTypeClass = Class.forName(PRIMITIVE_TYPE, true, loader);
            assertSame(loader, lyraTypeClass.getClassLoader());
            assertSame(loader, primitiveTypeClass.getClassLoader());
            assertTrue(lyraTypeClass.isInterface());
            assertTrue(primitiveTypeClass.isEnum());
            assertTrue(lyraTypeClass.isAssignableFrom(primitiveTypeClass));

            assertLyraTypeDeclaresNoConstants(lyraTypeClass);
            assertPrimitiveTypeDeclaresOnlyItsConstants(primitiveTypeClass);

            Object nilQualifier = loader.loadClass(TYPE_QUALIFIER).getField("NIL").get(null);
            Object[] constants = (Object[]) primitiveTypeClass.getMethod("values").invoke(null);
            assertEquals(14, constants.length, "the primitive universe has exactly fourteen constants");

            for (int index = 0; index < constants.length; index++) {
                Object constant = constants[index];
                String name = CONSTANT_NAMES.get(index);
                String spelling = SOURCE_SPELLINGS.get(index);
                boolean numeric = "I8".equals(name) || "I16".equals(name) || "I32".equals(name)
                        || "I64".equals(name) || "U8".equals(name) || "U16".equals(name)
                        || "U32".equals(name) || "U64".equals(name)
                        || "F32".equals(name) || "F64".equals(name);
                int bitWidth = numeric ? Integer.parseInt(name.substring(1)) : 0;
                boolean integer = numeric && !"F32".equals(name) && !"F64".equals(name);

                assertNotNull(constant, "primitive constant " + name + " must not be null");
                assertEquals(name, invoke(constant, "name"), "enum constant name");
                assertEquals(spelling, invoke(constant, "canonicalSpelling"),
                        name + " canonical spelling");
                assertEquals(spelling, invoke(constant, "canonical"), name + " canonical()");
                assertEquals(spelling, invoke(constant, "spelling"), name + " spelling()");
                assertEquals(spelling, invoke(constant, "toString"), name + " toString()");
                assertEquals(true, invoke(constant, "isPrimitive"), name + " isPrimitive()");
                assertEquals(false, invoke(constant, "isComposite"), name + " isComposite()");
                assertEquals(false, invoke(constant, "isNilable"), name + " isNilable()");
                assertEquals(false, invoke(constant, "isNullable"), name + " isNullable()");
                assertEquals(false, invoke(constant, "isMutable"), name + " isMutable()");
                assertEquals(numeric, invoke(constant, "isNumeric"), name + " isNumeric()");
                assertEquals(integer, invoke(constant, "isInteger"), name + " isInteger()");
                assertEquals(numeric && !integer, invoke(constant, "isFloating"),
                        name + " isFloating()");
                assertEquals(integer && !name.startsWith("U"), invoke(constant, "isSignedInteger"),
                        name + " isSignedInteger()");
                assertEquals(integer && name.startsWith("U"), invoke(constant, "isUnsignedInteger"),
                        name + " isUnsignedInteger()");
                assertEquals(numeric ? bitWidth : 0, invoke(constant, "bitWidth"),
                        name + " bitWidth()");
                assertSame(constant, invoke(constant, "withoutQualifiers"),
                        name + " withoutQualifiers()");
                assertSame(constant, invoke(constant, "baseType"), name + " baseType()");
                assertEquals(false, constant.getClass()
                                .getMethod("hasQualifier", loader.loadClass(TYPE_QUALIFIER))
                                .invoke(constant, nilQualifier),
                        name + " hasQualifier(NIL)");

                Object nilable = invoke(constant, "nilable");
                assertTrue(lyraTypeClass.isInstance(nilable), name + " nilable() contract");
                assertEquals(true, invoke(nilable, "isNilable"), name + " nilable().isNilable()");
                assertSame(constant, invoke(nilable, "baseType"), name + " nilable().baseType()");

                Object mutable = invoke(constant, "mutable");
                assertTrue(lyraTypeClass.isInstance(mutable), name + " mutable() contract");
                assertEquals(true, invoke(mutable, "isMutable"), name + " mutable().isMutable()");

                Object qualified = constant.getClass()
                        .getMethod("withQualifier", loader.loadClass(TYPE_QUALIFIER))
                        .invoke(constant, nilQualifier);
                assertEquals(true, invoke(qualified, "isNilable"),
                        name + " withQualifier(NIL).isNilable()");

                Object qualifiedSet = invokeWithSet(constant, "withQualifiers", nilQualifier);
                assertEquals(true, invoke(qualifiedSet, "isNilable"),
                        name + " withQualifiers(Set).isNilable()");

                Object minimum = invoke(constant, "minimumIntegerValue");
                Object maximum = invoke(constant, "maximumIntegerValue");
                assertEquals(integer, invoke(minimum, "isPresent"), name + " minimumIntegerValue()");
                assertEquals(integer, invoke(maximum, "isPresent"), name + " maximumIntegerValue()");
                if (integer) {
                    String expectedMinimum = name.startsWith("U") ? "0"
                            : "-" + java.math.BigInteger.ONE.shiftLeft(bitWidth - 1);
                    String expectedMaximum = java.math.BigInteger.ONE.shiftLeft(
                            name.startsWith("U") ? bitWidth : bitWidth - 1).subtract(
                            java.math.BigInteger.ONE).toString();
                    assertEquals(expectedMinimum, invoke(minimum, "get").toString(),
                            name + " minimum");
                    assertEquals(expectedMaximum, invoke(maximum, "get").toString(),
                            name + " maximum");
                }
            }

            assertNotSame(LyraType.class, lyraTypeClass,
                    "the isolated LyraType must not be the application class loader's copy");
            assertNotSame(PrimitiveType.class, primitiveTypeClass,
                    "the isolated PrimitiveType must not be the application class loader's copy");
        }
    }

    private static void assertLyraTypeDeclaresNoConstants(Class<?> lyraTypeClass) {
        assertEquals(0, lyraTypeClass.getDeclaredFields().length,
                "LyraType must declare no constant fields");
        for (String alias : REMOVED_ALIASES) {
            assertThrows(NoSuchFieldException.class, () -> lyraTypeClass.getField(alias),
                    "LyraType must not expose the removed alias field " + alias);
        }
    }

    private static void assertPrimitiveTypeDeclaresOnlyItsConstants(Class<?> primitiveTypeClass) {
        for (String name : CONSTANT_NAMES) {
            assertNotNull(field(primitiveTypeClass, name), "PrimitiveType owns " + name);
        }
        for (String alias : List.of("Bool", "Char", "String", "Unit")) {
            assertThrows(NoSuchFieldException.class, () -> primitiveTypeClass.getField(alias),
                    "PrimitiveType must not expose the removed alias field " + alias);
        }
        long aliasFields = java.util.Arrays.stream(primitiveTypeClass.getDeclaredFields())
                .filter(candidate -> java.lang.reflect.Modifier.isStatic(candidate.getModifiers()))
                .filter(candidate -> !candidate.isEnumConstant() && !candidate.isSynthetic())
                .count();
        assertEquals(0, aliasFields,
                "PrimitiveType declares only its enum constants, with no static alias fields");
    }

    private static Object field(Class<?> type, String name) {
        try {
            return type.getField(name);
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("missing field " + name + " on " + type.getName(), absent);
        }
    }

    private static Object invoke(Object receiver, String name) throws Exception {
        return receiver.getClass().getMethod(name).invoke(receiver);
    }

    private static Object invokeWithSet(Object receiver, String name, Object qualifier)
            throws Exception {
        Set<Object> qualifiers = Collections.singleton(qualifier);
        return receiver.getClass().getMethod(name, Set.class).invoke(receiver, qualifiers);
    }

    private static URLClassLoader isolatedLoader() throws Exception {
        Set<URL> urls = new LinkedHashSet<>();
        urls.add(PrimitiveTypeInitializationTest.class.getProtectionDomain()
                .getCodeSource().getLocation());
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                urls.add(new File(entry).toURI().toURL());
            }
        }
        return new URLClassLoader(new ArrayList<>(urls).toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader());
    }
}
