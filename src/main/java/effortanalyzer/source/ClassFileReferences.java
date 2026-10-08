package effortanalyzer.source;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Owner-resolved class and member references read from a class file constant pool. */
record ClassFileReferences(String thisClass, Set<String> classRefs, List<MemberRef> memberRefs) {

    static final ClassFileReferences EMPTY = new ClassFileReferences("", Set.of(), List.of());

    enum MemberKind { FIELD, METHOD }

    record MemberRef(MemberKind kind, String owner, String name, String descriptor) {
        String displayName() {
            String ownerName = owner.replace('/', '.');
            if (kind == MemberKind.FIELD) return ownerName + "." + name;
            if ("<init>".equals(name)) return "new " + ownerName + "(...)";
            return ownerName + "." + name + "()";
        }
    }

    static ClassFileReferences parse(byte[] classBytes) {
        if (classBytes == null || classBytes.length < 12) return EMPTY;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(classBytes))) {
            if (in.readInt() != 0xCAFEBABE) return EMPTY;
            in.readUnsignedShort();
            in.readUnsignedShort();
            int count = in.readUnsignedShort();
            if (count <= 1) return EMPTY;

            int[] tags = new int[count];
            String[] utf8 = new String[count];
            int[] first = new int[count];
            int[] second = new int[count];
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                tags[i] = tag;
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 3, 4 -> in.skipBytes(4);
                    case 5, 6 -> { in.skipBytes(8); i++; }
                    case 7, 8, 16, 19, 20 -> first[i] = in.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> {
                        first[i] = in.readUnsignedShort();
                        second[i] = in.readUnsignedShort();
                    }
                    case 15 -> in.skipBytes(3);
                    default -> { return EMPTY; }
                }
            }
            in.readUnsignedShort();
            int thisIndex = in.readUnsignedShort();

            Set<String> classes = new LinkedHashSet<>();
            List<MemberRef> members = new ArrayList<>();
            for (int i = 1; i < count; i++) {
                if (tags[i] == 7) {
                    String name = utf8At(utf8, first[i]);
                    if (!name.isEmpty() && name.charAt(0) != '[') classes.add(name);
                } else if (tags[i] == 9 || tags[i] == 10 || tags[i] == 11) {
                    String owner = utf8At(utf8, safeIndex(first, first[i], count));
                    int nat = second[i];
                    if (nat <= 0 || nat >= count || tags[nat] != 12 || owner.isEmpty() || owner.charAt(0) == '[') continue;
                    members.add(new MemberRef(tags[i] == 9 ? MemberKind.FIELD : MemberKind.METHOD, owner,
                            utf8At(utf8, first[nat]), utf8At(utf8, second[nat])));
                }
            }
            String self = thisIndex > 0 && thisIndex < count && tags[thisIndex] == 7 ? utf8At(utf8, first[thisIndex]) : "";
            return new ClassFileReferences(self, classes, members);
        } catch (IOException | RuntimeException e) {
            return EMPTY;
        }
    }

    private static int safeIndex(int[] first, int classIndex, int count) {
        return classIndex > 0 && classIndex < count ? first[classIndex] : 0;
    }

    private static String utf8At(String[] utf8, int index) {
        return index > 0 && index < utf8.length && utf8[index] != null ? utf8[index] : "";
    }
}
