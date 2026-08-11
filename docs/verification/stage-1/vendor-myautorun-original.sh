#!/system/bin/sh

#导出GPIO端口
echo 203 > /sys/class/gpio/export
#设置GPIO端口的方向
echo out > /sys/class/gpio/gpio203/direction

echo 1 > /sys/class/gpio/gpio203/value

while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 1
done



sleep 5

echo 'Myutorun!*******\r\n' > /dev/console
am start com.hy.yesobox/com.hy.yesobox.NoScreenActivity

