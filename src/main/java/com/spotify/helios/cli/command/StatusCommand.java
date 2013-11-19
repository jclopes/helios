/**
 * Copyright (C) 2013 Spotify AB
 */

package com.spotify.helios.cli.command;

import com.google.common.collect.Lists;

import com.spotify.helios.common.Client;
import com.spotify.helios.common.descriptors.AgentJob;

import net.sourceforge.argparse4j.inf.Argument;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;

import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static net.sourceforge.argparse4j.impl.Arguments.append;

public class StatusCommand extends ControlCommand {

  private final Argument jobArg;
  private final Argument hostsArg;

  public StatusCommand(final Subparser parser) {
    super(parser);

    jobArg = parser.addArgument("job")
        .help("Job id.");

    hostsArg = parser.addArgument("hosts")
        .action(append())
        .setDefault(Lists.newArrayList())
        .help("");
  }

  @Override
  int run(Namespace options, Client client, PrintStream out)
      throws ExecutionException, InterruptedException {
    final String container = jobArg.getDest();

    final List<String> agents = options.getList(hostsArg.getDest());

    for (final String agent : agents) {
      out.printf("%s: ", agent);
      final AgentJob agentJob = client.stat(agent, container).get();
      if (agentJob != null) {
        out.printf("%s%n", agentJob);
      } else {
        out.printf("-%n");
      }
    }

    return 0;
  }
}