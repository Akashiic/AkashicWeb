#!/bin/bash
IN=${IN:?path to a 720p test clip, e.g. Sintel}
OUT=${OUT:-./out}
T=30
run() { name=$1; shift; r=$(ffmpeg -hide_banner -loglevel info -benchmark -nostdin -y -t $T -i $IN "$@" 2>&1 | grep "bench: utime"); echo "$name $r"; }
run vp8_720_cpu16 -vf scale=1280:720 -c:v libvpx -deadline realtime -cpu-used 16 -b:v 2500k -minrate 2500k -maxrate 2500k -bufsize 2500k -lag-in-frames 0 -auto-alt-ref 0 -g 48 -threads 4 -an $OUT/vp8_720_16.webm
run vp8_480_cpu16 -vf scale=854:480 -c:v libvpx -deadline realtime -cpu-used 16 -b:v 1200k -minrate 1200k -maxrate 1200k -bufsize 1200k -lag-in-frames 0 -auto-alt-ref 0 -g 48 -threads 4 -an $OUT/vp8_480_16.webm
run vp8_720_cpu16_fps30 -vf scale=1280:720,fps=30 -c:v libvpx -deadline realtime -cpu-used 16 -b:v 2500k -minrate 2500k -maxrate 2500k -bufsize 2500k -lag-in-frames 0 -auto-alt-ref 0 -g 60 -threads 4 -an $OUT/vp8_720_16_30.webm
run vp9_720_cpu8_fps30 -vf scale=1280:720,fps=30 -c:v libvpx-vp9 -deadline realtime -cpu-used 8 -row-mt 1 -tile-columns 2 -b:v 1800k -minrate 1800k -maxrate 1800k -bufsize 1800k -lag-in-frames 0 -g 60 -threads 4 -an $OUT/vp9_720_30.webm
run vp9_480_cpu8_fps30 -vf scale=854:480,fps=30 -c:v libvpx-vp9 -deadline realtime -cpu-used 8 -row-mt 1 -tile-columns 1 -b:v 900k -minrate 900k -maxrate 900k -bufsize 900k -lag-in-frames 0 -g 60 -threads 4 -an $OUT/vp9_480_30.webm
