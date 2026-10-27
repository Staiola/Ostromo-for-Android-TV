#!/bin/zsh
# Makes the signing key for Ostromo for Android TV, once. Every release must be
# signed with this same key, or an update won't install over the last version,
# so keep ~/.android/ostromo-release.jks and its password safe (a password
# manager and a backup of the file). The password is typed here and goes only
# into ~/.android, readable by you alone.
#   scripts/make-android-key.sh
set -e
DIR=~/.android
KEY=$DIR/ostromo-release.jks
PROPS=$DIR/ostromo-release.properties
[[ -e $KEY ]] && { echo "$KEY already exists: keep using it"; exit 1; }
JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}
mkdir -p $DIR

read -s "PASS?Password for the key (6+ characters): "; echo
read -s "PASS2?Again: "; echo
[[ $PASS == $PASS2 ]] || { echo "the two passwords differ"; exit 1; }
[[ ${#PASS} -ge 6 ]] || { echo "too short"; exit 1; }
export PASS

$JAVA_HOME/bin/keytool -genkeypair -keystore $KEY -alias ostromo -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Ostromo, O=Staiola Studio" -storepass:env PASS -keypass:env PASS
chmod 600 $KEY

umask 077
printf 'storePassword=%s\nkeyAlias=ostromo\nkeyPassword=%s\n' "$PASS" "$PASS" > $PROPS
echo "made $KEY and $PROPS; back up the .jks file and the password"
