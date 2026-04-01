#!/bin/bash

#
# List and manage memory profiling reports
#

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "========================================"
echo "Memory Profiling Reports"
echo "========================================"
echo ""

cd "$PROJECT_DIR"

# Count different types of reports
VALGRIND_LOGS=$(ls valgrind_*.log 2>/dev/null | wc -l)
MASSIF_OUTS=$(ls massif.out.* 2>/dev/null | wc -l)
LEAK_SUMMARIES=$(ls leak_summary_*.txt 2>/dev/null | wc -l)
ASAN_LOGS=$(ls asan.log.* 2>/dev/null | wc -l)
LSAN_LOGS=$(ls lsan.log.* 2>/dev/null | wc -l)

echo "Report counts:"
echo "  Valgrind logs:     $VALGRIND_LOGS"
echo "  Massif profiles:   $MASSIF_OUTS"
echo "  Leak summaries:    $LEAK_SUMMARIES"
echo "  ASAN logs:         $ASAN_LOGS"
echo "  LSAN logs:         $LSAN_LOGS"
echo ""

# Show most recent reports
echo "========================================"
echo "Most Recent Reports (last 5)"
echo "========================================"
echo ""

if [[ $VALGRIND_LOGS -gt 0 ]]; then
    echo "Valgrind logs:"
    ls -lht valgrind_*.log 2>/dev/null | head -5 | awk '{print "  " $9 " - " $6 " " $7 " " $8 " - " $5}'
    echo ""
fi

if [[ $MASSIF_OUTS -gt 0 ]]; then
    echo "Massif profiles:"
    ls -lht massif.out.* 2>/dev/null | head -5 | awk '{print "  " $9 " - " $6 " " $7 " " $8 " - " $5}'
    echo ""
fi

if [[ $LEAK_SUMMARIES -gt 0 ]]; then
    echo "Leak summaries:"
    ls -lht leak_summary_*.txt 2>/dev/null | head -5 | awk '{print "  " $9 " - " $6 " " $7 " " $8 " - " $5}'
    echo ""
fi

if [[ $ASAN_LOGS -gt 0 ]]; then
    echo "ASAN logs:"
    ls -lht asan.log.* 2>/dev/null | head -5 | awk '{print "  " $9 " - " $6 " " $7 " " $8 " - " $5}'
    echo ""
fi

if [[ $LSAN_LOGS -gt 0 ]]; then
    echo "LSAN logs:"
    ls -lht lsan.log.* 2>/dev/null | head -5 | awk '{print "  " $9 " - " $6 " " $7 " " $8 " - " $5}'
    echo ""
fi

# Quick stats from most recent valgrind log
echo "========================================"
echo "Latest Valgrind Summary"
echo "========================================"
echo ""

LATEST_VALGRIND=$(ls -t valgrind_*.log 2>/dev/null | head -1)
if [[ -n "$LATEST_VALGRIND" ]]; then
    echo "From: $LATEST_VALGRIND"
    echo ""
    grep "LEAK SUMMARY" -A 6 "$LATEST_VALGRIND" 2>/dev/null | sed 's/^/  /' || echo "  (No leak summary found)"
else
    echo "No valgrind logs found."
fi

echo ""

# Quick stats from most recent massif profile
echo "========================================"
echo "Latest Massif Profile"
echo "========================================"
echo ""

LATEST_MASSIF=$(ls -t massif.out.* 2>/dev/null | head -1)
if [[ -n "$LATEST_MASSIF" ]]; then
    echo "From: $LATEST_MASSIF"
    echo ""
    if command -v ms_print &> /dev/null; then
        ms_print "$LATEST_MASSIF" | grep -A 5 "peak" | head -10 | sed 's/^/  /'
    else
        echo "  (Install ms_print to view summary)"
        echo "  Peak snapshot:"
        grep "^snapshot=" "$LATEST_MASSIF" | tail -1 | sed 's/^/    /'
    fi
else
    echo "No massif profiles found."
fi

echo ""
echo "========================================"
echo "Management Commands"
echo "========================================"
echo ""
echo "# View latest valgrind log:"
echo "less $LATEST_VALGRIND"
echo ""
echo "# View latest massif profile:"
echo "ms_print $LATEST_MASSIF | less"
echo ""
echo "# Clean old reports (keeps last 5 of each type):"
echo "ls -t valgrind_*.log | tail -n +6 | xargs rm -f"
echo "ls -t massif.out.* | tail -n +6 | xargs rm -f"
echo "ls -t leak_summary_*.txt | tail -n +6 | xargs rm -f"
echo ""
echo "# Clean ALL reports (DANGER!):"
echo "rm -f valgrind_*.log valgrind_*.xml massif.out.* massif_report_*.txt leak_summary_*.txt"
echo ""
echo "# Compare two reports:"
echo "diff leak_summary_TIMESTAMP1.txt leak_summary_TIMESTAMP2.txt"
echo ""

# Offer to show specific report
if [[ "$1" == "--show-latest" ]]; then
    echo "========================================"
    echo "Showing Latest Valgrind Log"
    echo "========================================"
    echo ""
    if [[ -n "$LATEST_VALGRIND" ]]; then
        less "$LATEST_VALGRIND"
    else
        echo "No valgrind logs found."
    fi
elif [[ "$1" == "--clean-old" ]]; then
    echo "========================================"
    echo "Cleaning Old Reports (keeping last 5)"
    echo "========================================"
    echo ""

    CLEANED=0

    if [[ $VALGRIND_LOGS -gt 5 ]]; then
        echo "Cleaning old valgrind logs..."
        ls -t valgrind_*.log | tail -n +6 | xargs rm -f
        CLEANED=$((CLEANED + VALGRIND_LOGS - 5))
    fi

    if [[ $MASSIF_OUTS -gt 5 ]]; then
        echo "Cleaning old massif profiles..."
        ls -t massif.out.* | tail -n +6 | xargs rm -f
        CLEANED=$((CLEANED + MASSIF_OUTS - 5))
    fi

    if [[ $LEAK_SUMMARIES -gt 5 ]]; then
        echo "Cleaning old leak summaries..."
        ls -t leak_summary_*.txt | tail -n +6 | xargs rm -f
        CLEANED=$((CLEANED + LEAK_SUMMARIES - 5))
    fi

    echo "Removed $CLEANED old reports."
    echo ""
elif [[ "$1" == "--help" || "$1" == "-h" ]]; then
    echo ""
    echo "Usage: $0 [OPTION]"
    echo ""
    echo "Options:"
    echo "  (none)         List all reports with summaries"
    echo "  --show-latest  View the latest valgrind log in less"
    echo "  --clean-old    Remove old reports, keeping last 5 of each type"
    echo "  --help, -h     Show this help message"
    echo ""
fi
