#!/usr/bin/env perl
use strict;
use warnings;

@ARGV == 1 or die "usage: scenario-expand.pl <scenario-file>\n";
my $path = $ARGV[0];
open my $fh, '<', $path or die "scenario-expand.pl: $path: $!\n";
my @all = <$fh>;
close $fh;
chomp @all;

my @blocks;
my @block;
my $in_fence = 0;
for my $line (@all) {
    if ($line =~ /^```/) {
        if ($in_fence) {
            push @blocks, [@block];
            @block = ();
            $in_fence = 0;
        } else {
            $in_fence = 1;
        }
        next;
    }
    push @block, $line if $in_fence;
}

my @lines = @all;
if (@blocks) {
    my ($scenario) = grep { join("\n", @$_) =~ /^\s*\@waitfor\b/m } @blocks;
    defined $scenario or die "scenario-expand.pl: no executable scenario code block in $path\n";
    @lines = @$scenario;
}

my @variables = qw(PERF_TRIALS PERF_WARMUP PERF_SUSTAIN_OPS PERF_PLAYERS REGION_SEP);

sub substitute_variables {
    my ($line) = @_;
    for my $name (@variables) {
        next unless exists $ENV{$name};
        my $value = $ENV{$name};
        $line =~ s/\b\Q$name\E\b/$value/g;
    }
    return $line;
}

sub expand_lines {
    my (@input) = @_;
    my @output;
    for (my $i = 0; $i < @input; $i++) {
        my $line = $input[$i];
        if ($line =~ /^\s*\@repeat\s+([A-Z][A-Z0-9_]*)\s*:\s*(.*)$/) {
            my ($name, $inline) = ($1, $2);
            exists $ENV{$name} or die "scenario-expand.pl: $name is not set\n";
            $ENV{$name} =~ /^\d+$/ or die "scenario-expand.pl: $name must be a non-negative integer\n";
            my @body;
            if (length $inline) {
                @body = split /\s+;\s+/, $inline;
            } else {
                while ($i + 1 < @input && $input[$i + 1] =~ /^\s{2,}\S/) {
                    my $child = $input[++$i];
                    $child =~ s/^\s{2}//;
                    push @body, $child;
                }
            }
            my @expanded = expand_lines(@body);
            for (1 .. $ENV{$name}) {
                push @output, @expanded;
            }
            next;
        }
        $line = substitute_variables($line);
        $line =~ s/\s+#.*$//;
        push @output, $line;
    }
    return @output;
}

print "$_\n" for expand_lines(@lines);
