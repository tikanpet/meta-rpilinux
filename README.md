# meta_rpilinux
Additional Yocto metalayer for Raspberry Pi for U-boot (using FIT-image), for secure boot etc.

My focus here is mostly issues related in the booting process. So this meta-layer has instructions for creating just small root-file system to decrease the build-time (see rpilinux-image.bb), which herits just 'core-image-minimal.bb'. 

## Setup for Yocto-environment with Raspberry BSP and meta_rpilinux

```
mkdir Yocto
cd Yocto
git clone -b scarthgap git://git.yoctoproject.org/git/poky.git
git clone -b scarthgap git://git.yoctoproject.org/meta-raspberrypi.git
git clone git@github.com:tikanpet/meta-rpilinux.git
cd poky
```

Setup environment for creating build-environment: 

 source oe-init-build-env
 
Set correct machine e.g. in your build/local.conf, e.g. for Raspberry PI4:

```
 MACHINE ?= "raspberrypi4-64"
```

Set these RaspberryPI-related metalayers in  build/bblayers.conf:

```
 meta-raspberrypi
 meta-rpilinux
```

Add wic-image type in your 'build/local.conf': 
 IMAGE_FSTYPES += "wic wic.bz2, wic.bmap"

Build the image for Raspberry PI4:
```
 bitbake rpilinux
```

You can verify the content of the target-image (untar '.wic.bz2' first):

```
  wic ls tmp/deploy/images/raspberrypi4-64/rpilinux-image-raspberrypi4-64.rootfs-20251215221802.wic
    Num     Start        End          Size      Fstype
     1       4194304    140509183    136314880  fat16
     2     142606336    197132287     54525952  ext4
```
You can list e.g. FAT16-partition for checking attached bootfiles: 
   wic ls tmp/deploy/images/raspberrypi4-64/rpilinux-image-raspberrypi4-64.rootfs-20251215221802.wic:1

Image can be written to SD-card e.g. with bmaptool (on debian-distributions: sudo apt install bmap-tools)
First after inserting SD-card into PC, check if any partitions (like /dev/mmcblk0p1) are automounted, and unmount those.

Then flash the image:

```
sudo bmaptool -d copy tmp/deploy/images/raspberrypi4-64/rpilinux-image-raspberrypi4-64.rootfs-xxxxx.wic.bz2 /dev/mmcblk0
```

## Optionally add U-boot

This additional bootloader is nice tool with it's built-in console to interact with the system during the boot process (for e.g. executing memory tests).
If you want to use U-boot, enable it in your build/local.conf:

RPI_USE_U_BOOT = "1"

If you also want to package Linux-kernel, RPI base device tree and overlays into single file called FIT-image (Flattened Image Tree), add also these lines in local conf:

```
  KERNEL_CLASSES += "kernel-fitimage"
  KERNEL_IMAGETYPE = "fitImage"
  KERNEL_BOOTCMD = "bootm"
  UBOOT_SIGN_ENABLE = "0"
  KERNEL_IMAGETYPE_UBOOT = "fitImage"
```

Notice that fitImage has to be loaded into RAM (by 'fatload') far away from, where the content of fitImage will next be loaded to (by 'bootm'). Otherwise when 'bootm' starts to extract kernel (or DTB) from fitImage to RAM, it might starts to overwrite 'fitImage', prohibiting device to boot...
I made a patch to U-boot configuration (under meta_rpilinux/recipes-bsp/u-boot) for getting U-boot to load fitImage to proper area (address defined in SYS_LOAD_ADDR), and for executing it. U-boot is also configured to use FIT-image format.
Commands are simply:

```
fatload mmc 0:1 ${loadaddr} fitImage
bootm ${loadaddr}
```

That's it. 'bootm' will load default configuration (see below), so proper kernel (well, that only available kernel) and DTB. Kernel seems to be loaded to '0x00008000' (see '.its' below) followed by DTB.

fitImage contains device tree and overlay blobs from kernel source tree, not from U-boot tree, so possibly these also have more updated features e.g. for your Raspberry HAT extensions than U-boot DTBs...

If U-boot was not installed, startxx.elf (x is your RaspberryPi board) would boot Linux kernel with the help of 'config.txt', where you set desired DTBs and overlays. When using U-boot, you can also do the selection, by setting desired configurations in 'bootm'-command's parameters.
I didn't test it yet, but in addition to default configuration 'bootm' can give one ore more overlays for the kernel:

```
 bootm ${loadaddr}#fdt-bcm2711-rpi-4-b.dtb#conf-gpio-ir.dtbo
```
Perhaps I'll include later some examples either in U-boot's boot-script, but anyway the loading of overlays can be tested directly in U-boot's console (by first stopping autoboot by pressing any key).

You can find source file for fitImage (after bitbaking) at least from 'tmp/work/raspberrypi4_64-poky-linux/linux-raspberrypi/6.6.63+git/deploy-linux-raspberrypi/fitImage-its-raspberrypi4-64.its', but I also added '.its-file' alongside this Readme-file for investigation.

```
        images {
                kernel-1 {
                        description = "Linux kernel";
                        ...
                        load = <0x00008000>;
                        entry = <0x00008000>;
                };
                fdt-bcm2711-rpi-4-b.dtb {
                        description = "Flattened Device Tree blob";
                        data = /incbin/("arch/arm64/boot/dts/broadcom/bcm2711-rpi-4-b.dtb");
                        ...
                };
                ...
                fdt-gpio-ir.dtbo {
                        description = "Flattened Device Tree blob";
                        data = /incbin/("arch/arm64/boot/dts/overlays/gpio-ir.dtbo");
                    ...
                };
                
        configurations {
                default = "conf-bcm2711-rpi-4-b.dtb";
                conf-bcm2711-rpi-4-b.dtb {
                        description = "1 Linux kernel, FDT blob";
                        kernel = "kernel-1";
                        fdt = "fdt-bcm2711-rpi-4-b.dtb";                       
                        ...
                };
                ...         
                conf-gpio-ir.dtbo {
                        description = "0 FDT blob";
                        fdt = "fdt-gpio-ir.dtbo";
                        ...
                };  
```

