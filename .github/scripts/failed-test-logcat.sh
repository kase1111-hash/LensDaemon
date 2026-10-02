#!/bin/sh
# Print the device log of each failed instrumentation test: from shortly
# before the test started (the previous test's teardown) to its end.
#
# Usage: failed-test-logcat.sh <logcat file, "adb logcat -v threadtime" format>
log="$1"
context=200
max_lines=600

grep -o 'TestRunner: failed: .*' "$log" | sed 's/^TestRunner: failed: //' | sort -u |
while IFS= read -r test; do
    start=$(grep -n -F "TestRunner: started: $test" "$log" | head -n 1 | cut -d: -f1)
    end=$(grep -n -F "TestRunner: finished: $test" "$log" | head -n 1 | cut -d: -f1)
    [ -n "$start" ] || continue
    [ -n "$end" ] || end=$(wc -l < "$log")
    from=$((start > context ? start - context : 1))
    echo "::group::logcat: $test"
    sed -n "${from},${end}p" "$log" | tail -n "$max_lines"
    echo "::endgroup::"
done
