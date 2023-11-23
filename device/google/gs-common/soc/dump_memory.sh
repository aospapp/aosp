#!/vendor/bin/sh
echo "------ ION HEAPS ------"
for d in $(ls -d /d/ion/*)
do
  if [ -f $d ]; then
    echo --- $d
    cat $d
  else
    for f in $(ls $d)
    do
      echo --- $d/$f
      cat $d/$f
    done
  fi
done

echo "------ dmabuf info ------"
cat "/d/dma_buf/bufinfo"

echo "------ Page Pinner - longterm pin ------"
cat "/sys/kernel/debug/page_pinner/buffer"

echo "------ CMA info ------"
for d in $(ls -d /sys/kernel/debug/cma/*)
do
  echo --- $d
  echo --- count; cat $d/count;
  echo --- used; cat $d/used;
  echo --- bitmap; cat $d/bitmap;
done

echo "------ Pixel CMA stat ------"
for d in $(ls -d /sys/kernel/pixel_stat/mm/cma/*); do
  if [ -f $d ]; then
    echo --- $d
    cat $d
  else
    for f in $(ls $d); do
      echo --- $d/$f
      cat $d/$f
    done
  fi
done

echo "------ Pixel Trace ------"
cat "/sys/kernel/tracing/instances/pixel/trace"
