#!/bin/bash
IN=${IN:?path to a 720p test clip, e.g. Sintel}
OUT=${OUT:-./out}
T=30
run() { name=$1; shift; r=$(ffmpeg -hide_banner -loglevel info -benchmark -nostdin -y -t $T -i $IN "$@" 2>&1 | grep "bench: utime"); echo "$name $r"; }
run decode_only_720 -an -f null -
run decode_scale_480 -vf scale=854:480 -an -f null -
run vp8_720_cpu8 -vf scale=1280:720 -c:v libvpx -deadline realtime -cpu-used 8 -b:v 2500k -minrate 2500k -maxrate 2500k -bufsize 2500k -lag-in-frames 0 -auto-alt-ref 0 -g 48 -threads 4 -an $OUT/vp8_720.webm
run vp8_480_cpu8 -vf scale=854:480 -c:v libvpx -deadline realtime -cpu-used 8 -b:v 1200k -minrate 1200k -maxrate 1200k -bufsize 1200k -lag-in-frames 0 -auto-alt-ref 0 -g 48 -threads 4 -an $OUT/vp8_480.webm
run vp9_720_cpu8 -vf scale=1280:720 -c:v libvpx-vp9 -deadline realtime -cpu-used 8 -row-mt 1 -tile-columns 2 -b:v 1800k -minrate 1800k -maxrate 1800k -bufsize 1800k -lag-in-frames 0 -g 48 -threads 4 -an $OUT/vp9_720.webm
run vp9_480_cpu8 -vf scale=854:480 -c:v libvpx-vp9 -deadline realtime -cpu-used 8 -row-mt 1 -tile-columns 1 -b:v 900k -minrate 900k -maxrate 900k -bufsize 900k -lag-in-frames 0 -g 48 -threads 4 -an $OUT/vp9_480.webm
run av1svt_720_p10 -vf scale=1280:720 -c:v libsvtav1 -preset 10 -b:v 1500k -g 48 -svtav1-params lp=4 -an $OUT/av1_720.mkv
run av1svt_720_p12 -vf scale=1280:720 -c:v libsvtav1 -preset 12 -b:v 1500k -g 48 -svtav1-params lp=4 -an $OUT/av1_720p12.mkv
run opus_128 -vn -c:a libopus -b:a 128k -application lowdelay $OUT/a.opus
run x264_720_vf -vf scale=1280:720 -c:v libx264 -preset veryfast -tune zerolatency -b:v 2500k -an $OUT/x264_720.mp4
