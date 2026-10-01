#!/bin/bash
# One-time setup of the offline toolchain build.sh expects (Linux x64, needs Java 11+, Node/npm, python3).
# Installs into /root/androidtools by default; set TOOLS=/your/path and edit the paths at the top of build.sh to match.
set -e
TOOLS=${TOOLS:-/root/androidtools}
mkdir -p "$TOOLS" && cd "$TOOLS"
[ -f package.json ] || npm init -y >/dev/null
npm install @drxiaozhi/minapk aaptjs3
chmod +x node_modules/aaptjs3/bin/x64/linux/aapt2
# ecj needs java.lang.invoke.LambdaMetafactory to compile lambdas; android.jar leaves it out, so build a stub for it.
S=$(mktemp -d)
mkdir -p "$S/src/java/lang/invoke" "$S/out"
cat > "$S/src/java/lang/invoke/LambdaMetafactory.java" <<'J'
package java.lang.invoke;
public final class LambdaMetafactory {
    public static final int FLAG_SERIALIZABLE = 1, FLAG_MARKERS = 2, FLAG_BRIDGES = 4;
    public static CallSite metafactory(MethodHandles.Lookup c, String n, MethodType t, MethodType s, MethodHandle i, MethodType d) throws LambdaConversionException { throw new UnsupportedOperationException(); }
    public static CallSite altMetafactory(MethodHandles.Lookup c, String n, MethodType t, Object... a) throws LambdaConversionException { throw new UnsupportedOperationException(); }
}
J
javac --patch-module java.base="$S/src" -d "$S/out" "$S/src/java/lang/invoke/LambdaMetafactory.java"
jar cf "$TOOLS/lambda-stubs.jar" -C "$S/out" .
rm -rf "$S"
echo "Toolchain ready in $TOOLS. Put your keystore at keystore/release.jks, then: KS_PASS=... ./build.sh"
