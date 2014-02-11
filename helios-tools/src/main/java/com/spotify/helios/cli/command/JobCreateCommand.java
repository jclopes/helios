/**
 * Copyright (C) 2013 Spotify AB
 */

package com.spotify.helios.cli.command;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

import com.spotify.helios.common.HeliosClient;
import com.spotify.helios.common.JobValidator;
import com.spotify.helios.common.Json;
import com.spotify.helios.common.descriptors.Job;
import com.spotify.helios.common.descriptors.PortMapping;
import com.spotify.helios.common.descriptors.ServiceEndpoint;
import com.spotify.helios.common.descriptors.ServicePorts;
import com.spotify.helios.common.protocol.CreateJobResponse;

import net.sourceforge.argparse4j.inf.Argument;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.base.Optional.fromNullable;
import static com.spotify.helios.common.descriptors.PortMapping.TCP;
import static java.util.regex.Pattern.compile;
import static net.sourceforge.argparse4j.impl.Arguments.append;
import static net.sourceforge.argparse4j.impl.Arguments.fileType;
import static net.sourceforge.argparse4j.impl.Arguments.storeTrue;

public class JobCreateCommand extends ControlCommand {

  private static final JobValidator JOB_VALIDATOR = new JobValidator();

  private final Argument quietArg;
  private final Argument nameArg;
  private final Argument versionArg;
  private final Argument imageArg;
  private final Argument envArg;
  private final Argument argsArg;
  private final Argument portArg;
  private final Argument registrationArg;
  private final Argument fileArg;

  public JobCreateCommand(final Subparser parser) {
    super(parser);

    parser.help("create a job");

    fileArg = parser.addArgument("-f", "--file")
        .type(fileType().acceptSystemIn())
        .help("Job configuration file. Options specified on the command line will be merged with" +
              "the contents of this file.");

    quietArg = parser.addArgument("-q")
        .action(storeTrue())
        .help("only print job id");

    nameArg = parser.addArgument("name")
        .nargs("?")
        .help("Job name");

    versionArg = parser.addArgument("version")
        .nargs("?")
        .help("Job version");

    imageArg = parser.addArgument("image")
        .nargs("?")
        .help("Container image");

    envArg = parser.addArgument("--env")
        .action(append())
        .setDefault(new ArrayList<String>())
        .nargs("+")
        .help("Environment variables");

    portArg = parser.addArgument("-p", "--port")
        .action(append())
        .setDefault(new ArrayList<String>())
        .nargs("+")
        .help("Port mapping. Specify an endpoint name and a single port (e.g. \"http=8080\") for " +
              "dynamic port mapping and a name=private:public tuple (e.g. \"http=8080:80\") for " +
              "static port mapping. E.g., foo=4711 will map the internal port 4711 of the " +
              "container to an arbitrary external port on the host. Specifying foo=4711:80 " +
              "will map internal port 4711 of the container to port 80 on the host. The " +
              "protocol will be TCP by default. For UDP, add /udp. E.g. quic=80/udp or " +
              "dns=53:53/udp. The endpoint name can be used when specify service registration " +
              "using -r/--register.");

    registrationArg = parser.addArgument("-r", "--register")
        .action(append())
        .setDefault(new ArrayList<String>())
        .nargs("+")
        .help("Nameless service registration. Specify a service name, the port name and a " +
              "protocol on the format service/protocol=port. E.g. -r website/tcp=http will " +
              "register the port named http with Nameless with the protocol tcp, essentially " +
              "exposing it in DNS as a _spotify-website._tcp SRV record. Protocol is optional and " +
              "default is hm so e.g. -r wiggum=hermes will expose the port named hermes as " +
              "_spotify-wiggum._hm. If there is only one port mapping this will be used by" +
              "default and it will be enough to specify only the nameless service name, e.g." +
              "-r wiggum.");

    argsArg = parser.addArgument("args")
        .nargs("*")
        .help("Command line arguments");
  }

  @Override
  int run(Namespace options, HeliosClient client, PrintStream out, final boolean json)
      throws ExecutionException, InterruptedException, IOException {

    final boolean quiet = options.getBoolean(quietArg.getDest());

    final Job.Builder builder;

    final String name = options.getString(nameArg.getDest());
    final String version = options.getString(versionArg.getDest());
    final String imageIdentifier = options.getString(imageArg.getDest());

    // Read job configuration from file

    // TODO (dano): look for e.g. Heliosfile in cwd by default?

    final File file = (File) options.get(fileArg.getDest());
    if (file != null && file.exists()) {
      if (!file.isFile() || !file.canRead()) {
        throw new IllegalArgumentException("Cannot read file " + file);
      }
      final byte[] bytes = Files.readAllBytes(file.toPath());
      final String config = new String(bytes, UTF_8);
      final Job job = Json.read(config, Job.class);
      builder = job.toBuilder();
    } else {
      if (name == null || version == null || imageIdentifier == null) {
        throw new IllegalArgumentException(
            "Please specify either a file or a job name, version and container image");
      }
      builder = Job.newBuilder();
    }

    // Merge job configuration options from command line arguments

    if (name != null) {
      builder.setName(name);
    }

    if (version != null) {
      builder.setVersion(version);
    }

    if (imageIdentifier != null) {
      builder.setImage(imageIdentifier);
    }

    // TODO (dano): make optional
    final List<String> command = options.getList(argsArg.getDest());
    if (command != null) {
      builder.setCommand(command);
    }

    final List<List<String>> envList = options.getList(envArg.getDest());
    if (!envList.isEmpty()) {
      final ImmutableMap.Builder<String, String> env = ImmutableMap.builder();
      env.putAll(builder.getEnv());
      for (final List<String> group : envList) {
        for (final String s : group) {
          final String[] parts = s.split("=", 2);
          if (parts.length != 2) {
            throw new IllegalArgumentException("Bad environment variable: " + s);
          }
          env.put(parts[0], parts[1]);
        }
      }
      builder.setEnv(env.build());
    }

    // Parse port mappings
    final List<List<String>> portSpecLists = options.getList(portArg.getDest());
    final Map<String, PortMapping> explicitPorts = Maps.newHashMap();
    final Pattern portPattern = compile("(?<n>[_\\-\\w]+)=(?<i>\\d+)(:(?<e>\\d+))?(/(?<p>\\w+))?");
    for (final List<String> specs : portSpecLists) {
      for (final String spec : specs) {
        final Matcher matcher = portPattern.matcher(spec);
        if (!matcher.matches()) {
          throw new IllegalArgumentException("Bad port mapping: " + spec);
        }

        final String portName = matcher.group("n");
        final int internal = Integer.parseInt(matcher.group("i"));
        final Integer external = nullOrInteger(matcher.group("e"));
        final String protocol = fromNullable(matcher.group("p")).or(TCP);

        if (explicitPorts.containsKey(portName)) {
          throw new IllegalArgumentException("Duplicate port mapping: " + portName);
        }

        explicitPorts.put(portName, PortMapping.of(internal, external, protocol));
      }
    }

    // Merge port mappings
    final Map<String, PortMapping> ports = Maps.newHashMap();
    ports.putAll(builder.getPorts());
    ports.putAll(explicitPorts);
    builder.setPorts(ports);

    // Parse service registrations
    final Map<ServiceEndpoint, ServicePorts> explicitRegistration = Maps.newHashMap();
    final Pattern registrationPattern =
        compile("(?<srv>[a-zA-Z][_\\-\\w]+)(?:/(?<prot>\\w+))?(?:=(?<port>[_\\-\\w]+))?");
    final List<List<String>> registrationSpecLists = options.getList(registrationArg.getDest());
    for (List<String> registrationSpecList : registrationSpecLists) {
      for (final String spec : registrationSpecList) {
        final Matcher matcher = registrationPattern.matcher(spec);
        if (!matcher.matches()) {
          throw new IllegalArgumentException("Bad registration: " + spec);
        }

        final String service = matcher.group("srv");
        final String proto = fromNullable(matcher.group("prot")).or("hm");
        final String optionalPort = matcher.group("port");
        final String port;

        if (ports.size() == 0) {
          throw new IllegalArgumentException("Need port mappings for service registration.");
        }

        if (optionalPort == null) {
          if (ports.size() != 1) {
            throw new IllegalArgumentException(
                "Need exactly one port mapping for implicit service registration");
          }
          port = Iterables.getLast(ports.keySet());
        } else {
          port = optionalPort;
        }

        explicitRegistration.put(ServiceEndpoint.of(service, proto), ServicePorts.of(port));
      }
    }

    // Merge service registrations
    final Map<ServiceEndpoint, ServicePorts> registration = Maps.newHashMap();
    registration.putAll(builder.getRegistration());
    registration.putAll(explicitRegistration);
    builder.setRegistration(registration);

    final Job job = builder.build();

    final Collection<String> errors = JOB_VALIDATOR.validate(job);
    if (!errors.isEmpty()) {
      // TODO: nicer output
      throw new IllegalArgumentException("Bad job definition: " + errors);
    }

    if (!quiet) {
      out.println("Creating job: " + job.toJsonString());
    }

    final CreateJobResponse status = client.createJob(job).get();
    if (status.getStatus() == CreateJobResponse.Status.OK) {
      if (!quiet) {
        out.println("Done.");
      }
      out.println(job.getId());
      return 0;
    } else {
      if (!quiet) {
        out.println("Failed: " + status);
      }
      return 1;
    }
  }

  private Integer nullOrInteger(final String s) {
    return s == null ? null : Integer.valueOf(s);
  }
}
