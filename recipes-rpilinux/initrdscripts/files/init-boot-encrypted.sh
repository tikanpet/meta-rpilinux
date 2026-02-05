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

# wait for root-device to appear
# TODO: add some logic for not waiting for ever (if device not available at all)
while [ ! -e "$ROOT_DEVICE" ]; do
    sleep 0.1
done

# this dummy delay is just for waiting for debug-messages to get
# Cryptsetup's passphrase-query to be better seen after most of the messages 
echo "Wait for most of the kernel debug-messages to be printed into the console..."
sleep 4.0

echo "Decrypting root file-system..."
cryptsetup open $ROOT_DEVICE decrypt

if [ ! -e "/dev/mapper/decrypt" ]; then
    echo "Decryption of root file system failed. Enter InitRamfs command shell."
    exec sh
fi

echo "Successfully decrypted!"

if [ -n "/first_boot" ]; then
    echo "This is first boot (brand new root file system). Resize the file system"
    resize2fs /dev/mapper/decrypt

    # remove first_boot file, so resize will not be done again during following bootups
    rm /first_boot
fi

mount /dev/mapper/decrypt /newroot

echo "Switching to real root filesystem..."
exec switch_root /newroot /sbin/init

echo "Switching failed. Enter InitRamfs command shell."
exec sh
