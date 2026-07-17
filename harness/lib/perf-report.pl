#!/usr/bin/env perl
use strict;
use warnings;
use FindBin qw($Bin);
use JSON::PP;

sub usage {
    die "usage: perf-report.sh <run.log> [--out <report.json>] [--require-region-threads [N]]\n";
}

@ARGV >= 1 or usage();
my $log_path = shift @ARGV;
my $out_path;
my $required_threads = 0;
while (@ARGV) {
    my $arg = shift @ARGV;
    if ($arg eq '--out') {
        @ARGV or usage();
        $out_path = shift @ARGV;
    } elsif ($arg eq '--require-region-threads') {
        if (@ARGV && $ARGV[0] =~ /^\d+$/) {
            $required_threads = shift @ARGV;
        } else {
            $required_threads = 2;
        }
    } else {
        usage();
    }
}
-f $log_path or die "perf-report.sh: log not found: $log_path\n";

sub read_lines {
    my ($path) = @_;
    return () unless -f $path;
    open my $fh, '<', $path or die "perf-report.sh: $path: $!\n";
    my @lines = <$fh>;
    close $fh;
    chomp @lines;
    return @lines;
}

sub kv {
    my ($line) = @_;
    my %values;
    while ($line =~ /(?:^|\s)([A-Za-z][A-Za-z0-9_]*)=([^\s]+)/g) {
        $values{$1} = $2;
    }
    return \%values;
}

sub percentile {
    my ($values, $fraction) = @_;
    return undef unless @$values;
    my @sorted = sort { $a <=> $b } @$values;
    my $index = int($fraction * @sorted + 0.999999) - 1;
    $index = 0 if $index < 0;
    return 0 + $sorted[$index];
}

sub operation_summary {
    my ($rows, $source) = @_;
    my @elapsed = map { 0 + $_->{elapsed_ms} }
            grep { exists $_->{elapsed_ms} && $_->{elapsed_ms} =~ /^\d+(?:\.\d+)?$/ } @$rows;
    my $blocks = 0;
    my $elapsed_total = 0;
    my $ok = 0;
    for my $row (@$rows) {
        $blocks += $row->{blocks} if exists $row->{blocks} && $row->{blocks} =~ /^\d+$/;
        $elapsed_total += $row->{elapsed_ms}
                if exists $row->{elapsed_ms} && $row->{elapsed_ms} =~ /^\d+(?:\.\d+)?$/;
        $ok++ if ($row->{result} // '') eq 'ok';
    }
    my %summary = (
        source => $source,
        operations => scalar(@$rows),
        ok => $ok,
    );
    if (@elapsed) {
        $summary{elapsed_ms} = {
            p50 => percentile(\@elapsed, 0.50),
            p95 => percentile(\@elapsed, 0.95),
            p99 => percentile(\@elapsed, 0.99),
            max => percentile(\@elapsed, 1.00),
        };
    }
    $summary{blocks} = $blocks if $blocks;
    $summary{blocks_per_second} = 0 + sprintf('%.3f', $blocks * 1000 / $elapsed_total)
            if $blocks && $elapsed_total > 0;
    return \%summary;
}

my @log = read_lines($log_path);
my @marks_raw = read_lines("$log_path.marks.tsv");
my @rss_raw = read_lines("$log_path.perf.tsv");
my @threads_raw = read_lines("$log_path.threads.tsv");
my @gc_raw = read_lines("$log_path.gc.log");

my %report = (
    schema => 'fawe-harness-perf-v1',
    log => $log_path,
);

my @marks;
for my $line (@marks_raw) {
    my ($epoch, $iso, $label) = split /\t/, $line, 3;
    next unless defined $label && $epoch =~ /^\d+$/;
    push @marks, { epoch_ms => 0 + $epoch, iso => $iso, label => $label };
}
if (@marks) {
    my @windows;
    my %begin;
    for my $mark (@marks) {
        if ($mark->{label} =~ /^(.*):begin$/) {
            $begin{$1} = $mark;
        } elsif ($mark->{label} =~ /^(.*):end$/ && exists $begin{$1}) {
            push @windows, {
                label => $1,
                begin_epoch_ms => $begin{$1}->{epoch_ms},
                end_epoch_ms => $mark->{epoch_ms},
                elapsed_ms => $mark->{epoch_ms} - $begin{$1}->{epoch_ms},
            };
            delete $begin{$1};
        }
    }
    $report{marks} = \@marks;
    $report{marked_windows} = \@windows if @windows;
}

my (@fawe_rows, @probe_rows, @probe_commits, @probe_regions, @fawe_queues, @probe_queues, @region_samples);
my @tps_samples;
for my $line (@log) {
    if ($line =~ /\bFAWE_PROBE_PERF\b/) {
        push @probe_rows, kv($line);
    } elsif ($line =~ /\bFAWE_PERF\b/) {
        push @fawe_rows, kv($line);
    }
    push @probe_commits, kv($line) if $line =~ /\bFAWE_PROBE_COMMIT\b/;
    push @probe_regions, kv($line) if $line =~ /\bFAWE_PROBE_REGION\b/ && $line !~ /\bFAWE_PROBE_REGION_SAMPLE\b/;
    if ($line =~ /\bFAWE_PROBE_QUEUE\b/) {
        push @probe_queues, kv($line);
    } elsif ($line =~ /\bFAWE_QUEUE\b/) {
        push @fawe_queues, kv($line);
    }
    push @region_samples, kv($line) if $line =~ /\bFAWE_PROBE_REGION_SAMPLE\b/;
    if ($line =~ /TPS[^:]*:\s*([0-9.]+)(?:,\s*|\s+)([0-9.]+)(?:,\s*|\s+)([0-9.]+)/i) {
        push @tps_samples, [0 + $1, 0 + $2, 0 + $3];
    }
}
$report{fawe_operations} = operation_summary(\@fawe_rows, 'FAWE_PERF') if @fawe_rows;
$report{probe_operations} = operation_summary(\@probe_rows, 'FAWE_PROBE_PERF') if @probe_rows;

if (@probe_commits) {
    my @schedule = map { 0 + $_->{max_schedule_delay_us} }
            grep { ($_->{max_schedule_delay_us} // '') =~ /^\d+$/ } @probe_commits;
    my @commit = map { 0 + $_->{max_commit_task_us} }
            grep { ($_->{max_commit_task_us} // '') =~ /^\d+$/ } @probe_commits;
    $report{probe_commit_timers} = {
        source => 'FAWE_PROBE_COMMIT',
        summaries => scalar(@probe_commits),
        (@schedule ? (max_schedule_delay_us => percentile(\@schedule, 1.0)) : ()),
        (@commit ? (max_commit_task_us => percentile(\@commit, 1.0)) : ()),
    };
}

sub queue_summary {
    my ($rows, $source) = @_;
    my %summary = (source => $source, samples => scalar(@$rows));
    for my $field (qw(depth inflight outstanding depth_highwater inflight_highwater)) {
        my @values = map { 0 + $_->{$field} } grep { ($_->{$field} // '') =~ /^\d+$/ } @$rows;
        $summary{"max_$field"} = percentile(\@values, 1.0) if @values;
    }
    return \%summary;
}
$report{fawe_queue} = queue_summary(\@fawe_queues, 'FAWE_QUEUE') if @fawe_queues;
$report{probe_queue} = queue_summary(\@probe_queues, 'FAWE_PROBE_QUEUE') if @probe_queues;
$report{probe_region_commit_timers} = \@probe_regions if @probe_regions;
$report{probe_region_samples} = \@region_samples if @region_samples;
if (@region_samples && exists $report{marked_windows}) {
    my %sample_by_key;
    for my $sample (@region_samples) {
        next unless exists $sample->{label} && exists $sample->{group};
        $sample_by_key{"$sample->{label}\0$sample->{group}"} = $sample;
    }
    my @region_windows;
    for my $window (@{$report{marked_windows}}) {
        for my $group (0 .. 3) {
            my $before = $sample_by_key{"$window->{label}:begin\0$group"};
            my $after = $sample_by_key{"$window->{label}:end\0$group"};
            next unless $before && $after;
            my %row = (
                label => $window->{label},
                group => $group,
                source => 'rolling_5s_probe_samples',
                tps_before => $before->{tps_5s},
                tps_after => $after->{tps_5s},
            );
            if (($before->{tick_avg_ns} // '') =~ /^-?\d+$/
                    && ($after->{tick_avg_ns} // '') =~ /^-?\d+$/) {
                $row{tick_avg_before_ns} = 0 + $before->{tick_avg_ns};
                $row{tick_avg_after_ns} = 0 + $after->{tick_avg_ns};
                $row{tick_avg_delta_ns} = $after->{tick_avg_ns} - $before->{tick_avg_ns};
            }
            push @region_windows, \%row;
        }
    }
    $report{probe_region_windows} = \@region_windows if @region_windows;
}
$report{tps_samples} = \@tps_samples if @tps_samples;

my @rss;
my @sampler_exits;
for my $line (@rss_raw) {
    my ($epoch, $kb, $event) = split /\t/, $line, 3;
    push @sampler_exits, 0 + $epoch
            if defined $event && $event eq 'process_exit' && $epoch =~ /^\d+$/;
    push @rss, [0 + $epoch, 0 + $kb] if defined $kb && $epoch =~ /^\d+$/ && $kb =~ /^\d+$/;
}
if (@rss) {
    my @values = map { $_->[1] } @rss;
    my $peak = percentile(\@values, 1.0);
    $report{rss_kb} = {
        source => 'ps -o rss',
        samples => scalar(@rss),
        first => $rss[0]->[1],
        peak => $peak,
        delta_from_first => $peak - $rss[0]->[1],
    };
    my @stop_marks = grep { $_->{label} eq 'op:stop:begin' } @marks;
    if (@stop_marks) {
        my $begin = $stop_marks[-1]->{epoch_ms};
        my @after = grep { $_ >= $begin } @sampler_exits;
        if (@after) {
            $report{shutdown_drain_upper_bound_ms} = $after[-1] - $begin;
            $report{shutdown_drain_source} = 'sampler_process_exit_observation';
        }
    }
}

my @gc_pauses;
for my $line (@gc_raw) {
    push @gc_pauses, 0 + $1 if $line =~ /\bPause\b.*?([0-9]+(?:\.[0-9]+)?)ms\b/;
}
if (@gc_pauses) {
    my $total = 0; $total += $_ for @gc_pauses;
    $report{gc_pause_ms} = {
        source => 'JVM unified GC log',
        samples => scalar(@gc_pauses),
        total => 0 + sprintf('%.3f', $total),
        p99 => percentile(\@gc_pauses, 0.99),
        max => percentile(\@gc_pauses, 1.0),
    };
}

my %region_threads;
for my $line (@threads_raw) {
    my (undef, $name) = split /\t/, $line, 2;
    next unless defined $name;
    $region_threads{$name} = 1 if $name =~ /(?:Folia[_ ]?)?Region[_ ]Scheduler[_ ]Thread/i;
}
if (@threads_raw) {
    my @names = sort keys %region_threads;
    $report{region_thread_attribution} = {
        source => 'jstack FAWE/WorldEdit frames',
        distinct_region_threads => scalar(@names),
        threads => \@names,
    };
    if ($required_threads && @names < $required_threads) {
        print STDERR "perf-report.sh: required $required_threads distinct region threads, observed " . scalar(@names) . "\n";
        exit 1;
    }
} elsif ($required_threads) {
    print STDERR "perf-report.sh: region-thread attribution was required but no thread sample exists\n";
    exit 1;
}

my $versions_path = "$Bin/../versions.env";
my %coverage;
for my $line (read_lines($versions_path)) {
    if ($line =~ /^FOLIA_(26_1_[12])_BUILD=(.*)$/) {
        (my $version = $1) =~ tr/_/./;
        $coverage{$version} = $2 eq 'UNSTAGED' ? 'unstaged' : 'staged';
    }
}
$report{version_coverage} = \%coverage if %coverage;
$report{pending} = {
    fawe_driver => 'wave_1',
    note => 'Probe metrics are reported separately and are not FAWE EditSession measurements',
} unless @fawe_rows;

my $json = JSON::PP->new->canonical->pretty->encode(\%report);
if (defined $out_path) {
    open my $out, '>', $out_path or die "perf-report.sh: $out_path: $!\n";
    print {$out} $json;
    close $out;
}
print $json;
