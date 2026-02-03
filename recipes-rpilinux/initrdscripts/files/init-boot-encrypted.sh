#!/bin/sh

PATH=/sbin:/bin:/usr/sbin:/usr/bin

mkdir /proc
mkdir /sys
mkdir /run
mkdir /newroot
mount -t proc proc /proc
mount -t sysfs sysfs /sys
mount -t devtmpfs none /dev

CMDLINE=$(cat /proc/cmdline)
for param in $CMDLINE; do
    case $param in
        root=*)
            ROOT_DEVICE=${param#root=}
            ;;
    esac
done

if [ -z "$ROOT_DEVICE" ]; then
    echo "No root device specified in kernel command line."
    exec sh
fi

while [ ! -e "$ROOT_DEVICE" ]; do
    sleep 0.1
done

echo "Decrypting root file-system..."
cryptsetup open $ROOT_DEVICE decrypt

if [ ! -e "/dev/mapper/decrypt" ]; then
    echo "Decryption of root file system failed. Enter InitRamfs command shell."
    exec sh
fi

echo "Successfully decrypted!"
mount /dev/mapper/decrypt /newroot

echo "Switching to real root filesystem..."
exec switch_root /newroot /sbin/init

echo "Switching failed. Enter InitRamfs command shell."
exec sh
