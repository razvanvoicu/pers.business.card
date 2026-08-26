package sgrv.be

import sgrv.be.core.*
import zio.*
import zio.http.{Method, Path, Request, Response, Routes, Status, URL, handler}

object EchoPlugin extends BackendPlugin:
  type Requires = Any

  override val id = "test-echo"
  override val requirements: CapabilitySet[Requires] = CapabilitySet.empty
  override val accessPolicy: AccessPolicy[Requires] = AccessPolicy.Public
  override val routes: Routes[Requires & RequestContext, Nothing] = Routes(
    Method.POST / "echo" -> handler((request: Request) => Response.text(s"${request.method.name}:${request.url.path}")),
    Method.PUT / "echo" -> handler((request: Request) => Response.text(s"${request.method.name}:${request.url.path}"))
  )

trait Greeter:
  def greeting: String

object GreeterCapability:
  val capability: Capability[Greeter] = Capability("test-greeter")

/** A plugin gated on a single capability, standing in for a real capability-requiring plugin (like the app's own
  * `MessageStore`/`CardConfig`-gated ones) to exercise capability resolution without depending on either.
  */
object GreetingPlugin extends BackendPlugin:
  type Requires = Greeter

  override val id = "test-greeting"
  override val requirements: CapabilitySet[Requires] = CapabilitySet.one(GreeterCapability.capability)
  override val accessPolicy: AccessPolicy[Requires] = AccessPolicy.Public
  override val routes: Routes[Requires & RequestContext, Nothing] =
    Routes(Method.GET / "greeting" -> handler(ZIO.serviceWith[Greeter](greeter => Response.text(greeter.greeting))))

object IncompatiblePlugin extends BackendPlugin:
  type Requires = Any

  override val id = "test-incompatible"
  override val apiVersion = BackendPlugin.ApiVersion + 1
  override val requirements: CapabilitySet[Requires] = CapabilitySet.empty
  override val accessPolicy: AccessPolicy[Requires] = AccessPolicy.Public
  override val routes: Routes[Requires & RequestContext, Nothing] = Routes.empty

object FailedPlugin extends BackendPlugin:
  type Requires = Any

  override val id = "test-failed"
  override val requirements: CapabilitySet[Requires] = CapabilitySet.empty
  override val accessPolicy: AccessPolicy[Requires] = AccessPolicy.Public
  override def routes: Routes[Requires & RequestContext, Nothing] =
    throw new IllegalStateException("activation failed")

object InvalidPlugin

trait TestAlpha:
  def value: String

trait TestBeta:
  def value: String

class RouteDiscoverySuite extends munit.FunSuite:
  private val greeter: Greeter = new Greeter:
    override def greeting: String = "hello"

  private val greeterRegistry = CapabilityRegistry.fromEnvironment(ZEnvironment(greeter))

  private def run[A](effect: Task[A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }

  test("loads a nominal plugin object and activates its native routes"):
    val loaded = run(RouteDiscovery.load(EchoPlugin.getClass.getName, getClass.getClassLoader))
    val status = RouteDiscovery.activate(loaded, EchoPlugin.getClass.getName, CapabilityRegistry.empty)
    val routes = RouteDiscovery.fromStatuses(Seq(status))
    val request = Request(method = Method.POST, url = URL.decode("/echo").toOption.get)
    val response = run(ZIO.scoped(routes.runZIO(request)))

    assertEquals(loaded.id, "test-echo")
    assertEquals(run(response.body.asString), "POST:/echo")
    assert(routes.routes.exists(_.routePattern.matches(Method.PUT, Path("/echo"))))

  test("skips a plugin when its typed capability requirements cannot be resolved"):
    val status = RouteDiscovery.activate(GreetingPlugin, GreetingPlugin.getClass.getName, CapabilityRegistry.empty)

    status match
      case PluginStatus.Skipped(id, _, missing) =>
        assertEquals(id, "test-greeting")
        assertEquals(missing.map(_.id), Chunk("test-greeter"))
      case other => fail(s"Expected a skipped plugin, got $other")

  test("closes an activated plugin over its resolved environment"):
    val status = RouteDiscovery.activate(GreetingPlugin, GreetingPlugin.getClass.getName, greeterRegistry)
    val routes = RouteDiscovery.fromStatuses(Seq(status))
    val request = Request.get(URL.decode("/greeting").toOption.get)
    val response = run(ZIO.scoped(routes.runZIO(request)))

    assertEquals(response.status, Status.Ok)
    assertEquals(run(response.body.asString), "hello")

  test("resolves a composed capability set with its intersection type intact"):
    val alpha: TestAlpha = new TestAlpha:
      override val value = "alpha"
    val beta: TestBeta = new TestBeta:
      override val value = "beta"
    val alphaCapability = Capability[TestAlpha]("test-alpha")
    val betaCapability = Capability[TestBeta]("test-beta")
    val registry = CapabilityRegistry.fromEnvironment(ZEnvironment(alpha, beta))
    val plugin = new BackendPlugin:
      type Requires = TestAlpha & TestBeta

      override val id = "test-composed-environment"
      override val requirements: CapabilitySet[Requires] =
        CapabilitySet.one(alphaCapability) ++ CapabilitySet.one(betaCapability)
      override val accessPolicy: AccessPolicy[Requires] = AccessPolicy.Public
      override val routes: Routes[Requires & RequestContext, Nothing] = Routes(
        Method.GET / "composed" -> handler:
          for
            a <- ZIO.service[TestAlpha]
            b <- ZIO.service[TestBeta]
          yield Response.text(s"${a.value}:${b.value}")
      )
    val status = RouteDiscovery.activate(plugin, "ComposedPlugin", registry)
    val routes = RouteDiscovery.fromStatuses(Seq(status))
    val response = run(ZIO.scoped(routes.runZIO(Request.get(URL.decode("/composed").toOption.get))))
    val missing = RouteDiscovery.activate(plugin, "ComposedPlugin", CapabilityRegistry.empty)

    assertEquals(run(response.body.asString), "alpha:beta")
    missing match
      case PluginStatus.Skipped(_, _, capabilities) =>
        assertEquals(capabilities.map(_.id), Chunk("test-alpha", "test-beta"))
      case other => fail(s"Expected both missing capabilities, got $other")

  test("rejects a plugin compiled for an incompatible plugin API"):
    val status =
      RouteDiscovery.activate(IncompatiblePlugin, IncompatiblePlugin.getClass.getName, CapabilityRegistry.empty)

    status match
      case PluginStatus.Rejected(_, reason) => assert(reason.contains("API version"), reason)
      case other                            => fail(s"Expected a rejected plugin, got $other")

  test("isolates a plugin that throws while constructing its routes"):
    val status = RouteDiscovery.activate(FailedPlugin, FailedPlugin.getClass.getName, CapabilityRegistry.empty)

    status match
      case PluginStatus.Failed(id, _, cause) =>
        assertEquals(id, "test-failed")
        assertEquals(cause.getMessage, "activation failed")
      case other => fail(s"Expected a failed plugin, got $other")

  test("rejects every plugin involved in an overlapping route pattern"):
    val first = PluginStatus.Active(
      "first",
      "First",
      Routes(Method.GET / "collision" -> handler(Response.ok))
    )
    val second = PluginStatus.Active(
      "second",
      "Second",
      Routes(Method.GET / "collision" -> handler(Response.ok))
    )
    val statuses = RouteDiscovery.rejectConflicts(Seq(first, second))

    assert(statuses.forall(_.isInstanceOf[PluginStatus.Rejected]), statuses)
    assertEquals(RouteDiscovery.fromStatuses(statuses).routes.size, 0)

  test("rejects every active plugin sharing the same stable id"):
    val first = PluginStatus.Active("duplicate", "First", Routes.empty)
    val second = PluginStatus.Active("duplicate", "Second", Routes.empty)
    val statuses = RouteDiscovery.rejectDuplicateIds(Seq(first, second))

    assert(statuses.forall(_.isInstanceOf[PluginStatus.Rejected]), statuses)

  test("rejects a dynamically requested route pattern reserved by the static application"):
    val active = PluginStatus.Active(
      "reserved",
      "Reserved",
      Routes(Method.GET / "index.html" -> handler(Response.ok))
    )
    val statuses = RouteDiscovery.rejectConflicts(Seq(active), Set(Method.GET / "index.html"))

    assert(statuses.head.isInstanceOf[PluginStatus.Rejected], statuses)

  test("rejects a module that does not implement the nominal plugin contract"):
    val result = run(RouteDiscovery.load(InvalidPlugin.getClass.getName, getClass.getClassLoader).either)

    result match
      case Left(error: IllegalArgumentException) => assert(error.getMessage.contains("does not implement"), error)
      case Left(error) => fail(s"Expected IllegalArgumentException, got ${error.getClass.getName}: ${error.getMessage}")
      case Right(value) => fail(s"Expected plugin loading to fail, got $value")

  test("discovers plugins independently and keeps their activation outcomes"):
    val statuses = run(RouteDiscovery.discover(greeterRegistry))

    assert(statuses.exists {
      case PluginStatus.Active("test-echo", _, _) => true
      case _                                      => false
    })
    assert(statuses.exists {
      case PluginStatus.Rejected(className, reason) =>
        className.contains("IncompatiblePlugin") && reason.contains("API version")
      case _ => false
    })
    assert(statuses.exists {
      case PluginStatus.Failed("test-failed", _, _) => true
      case _                                        => false
    })
