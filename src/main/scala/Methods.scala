package tugboat

import dispatch.{ as, Req }
import dispatch.stream.Strings
import java.io.File
import org.json4s.JsonDSL._
import org.json4s.{ JArray, JBool, JInt, JObject, JString, JValue }
import org.json4s.native.JsonMethods.render
import org.json4s.native.Printer.compact
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

trait Methods { self: Requests =>

  private object json {
    private[this] val Typ = "application/json"
    private[this] val Encoding = "UTF-8"
    def content(r: Req) = r.setContentType(Typ, Encoding)
    def str(jv: JValue) = compact(render(jv))
  }

  case class Auth(
    _user: String,
    _password: String,
    _email: String,
    _server: String = "https://index.docker.io/v1/")
    extends Client.Completion[Unit] {
    def user(u: String) = copy(_user = u)
    def password(pw: String) = copy(_password = pw)
    def email(em: String) = copy(_email = em)
    def server(svr: String) = copy(_server = svr)
    def config(cfg: AuthConfig) = copy(
      _user = cfg.user,
      _password = cfg.password,
      _email = cfg.email,
      _server = cfg.server
    )
    def apply[T](handler: Client.Handler[T]) =
      request(json.content(host / "auth") <<
              json.str(
                ("username" -> _user) ~
                ("password" -> _password) ~
                ("email" -> _email) ~
                ("serveraddress" -> _server)))(handler)
  }

  def auth(user: String, password: String, email: String): Auth =
    Auth(user, password, email)

  def auth(cfg: AuthConfig): Auth =
    Auth(cfg.user, cfg.password, cfg.email, cfg.password)

  object containers {
    private[this] def base = host / "containers"

    case class Containers(
      _all: Option[Boolean]           = None,
      _limit: Option[Int]             = None,
      _since: Option[FiniteDuration]  = None,
      _before: Option[FiniteDuration] = None,
      _sizes: Option[Boolean]         = None)
      extends Client.Completion[List[tugboat.Container]] {
      def all = copy(_all = Some(true))
      def limit(lim: Int) = copy(_limit = Some(lim))
      def since(s: FiniteDuration) = copy(_since = Some(s))
      def before(b: FiniteDuration) = copy(_before = Some(b))
      def sizes(include: Boolean) = copy(_sizes = Some(include))
      def apply[T](handler: Client.Handler[T]) =
        request(base / "json" <<?
               (Map.empty[String, String]
                ++ _all.map(("all" -> _.toString))
                ++ _limit.map(("limit"   -> _.toString))
                ++ _before.map(("before" -> _.toSeconds.toString))
                ++ _since.map(("since"   -> _.toSeconds.toString))
                ++ _sizes.map(("size"    -> _.toString))))(handler)
    }    

    // ( aka docker run )
    case class Create(
      _config: ContainerConfig,
      _name: Option[String] = None)
      extends Client.Completion[Option[tugboat.Create.Response]] {
      def name(n: String) = copy(_name = Some(n))
      def config(cfg: ContainerConfig) = copy(_config = cfg)
      def image(img: String) = config(
        _config.copy(image = img)
      )
      def apply[T](handler: Client.Handler[T]) =
        request(json.content(base.POST) / "create" <<?
                (Map.empty[String, String]
                 ++ _name.map(("name" -> _))) << bodyStr)(handler)

      def bodyStr = json.str(
                  ("Hostname"        -> _config.hostname) ~
                  ("User"            -> _config.user) ~
                  ("Memory"          -> _config.memory) ~
                  ("MemorySwap"      -> _config.memorySwap) ~
                  ("AttachStdin"     -> _config.attachStdin) ~
                  ("AttachStdout"    -> _config.attachStdout) ~
                  ("AttachStderr"    -> _config.attachStderr) ~
                  ("Tty"             -> _config.tty) ~
                  ("OpenStdin"       -> _config.openStdin) ~
                  ("StdinOnce"       -> _config.stdinOnce) ~
                  ("Cmd"             -> _config.cmd) ~
                  ("Image"           -> _config.image) ~
                  ("WorkingDir"      -> _config.workingDir) ~
                  ("NetworkDisabled" -> _config.networkDisabled))
    }

    case class Container(id: String)
      extends Client.Completion[Option[ContainerDetails]] {

      // todo host config
      case class Start()
        extends Client.Completion[Unit] {
        def apply[T](handler: Client.Handler[T]) =
          request(base.POST / id / "start")(handler)
      }

      case class Kill(
        _signal: Option[String] = None)
        extends Client.Completion[Unit] {
        def signal(sig: String) = copy(_signal = Some(sig))
        def apply[T](handler: Client.Handler[T]) =
          request(base.POST / id / "kill" <<?
                 (Map.empty[String, String]
                  ++ _signal.map(("signal" -> _))))(handler)
      }

      // todo: stream...
      case class Logs()
        extends Client.Completion[Unit] {
        def apply[T](handler: Client.Handler[T]) =
          request(base / id / "logs")(handler)
      }

      case class Delete(
        _volumes: Option[Boolean] = None,
        _force: Option[Boolean]   = None)
        extends Client.Completion[Unit] {
        def volumes(v: Boolean) = copy(_volumes = Some(v))
        def force(f: Boolean) = copy(_force = Some(f))
        def apply[T](handler: Client.Handler[T]) =
          request(base.DELETE / id <<?
                 (Map.empty[String, String]
                  ++ _volumes.map(("v" -> _.toString))
                  ++ _force.map(("force" -> _.toString))))(handler)
      }

      def apply[T](handler: Client.Handler[T]) =
        request(base / id / "json")(handler)

      def top(args: String = "") =
        complete[Top](base / id / "top" <<? Map("ps_args" -> args))
      
      def logs = Logs()

      def changes =
        complete[List[Change]](base / id / "changes")
      
      // todo octet stream
      def export[T](handler: Client.Handler[T]) =
        request(base / id / "export")(handler)

      def start = Start()

      def stop(after: Int = 0) =
        complete[Unit](base.POST / id / "stop" <<? Map("t" -> after.toString))

      def restart(after: Int = 0) =
        complete[Unit](base.POST / id / "restart" <<? Map("t" -> after.toString))

      def kill = Kill()

      // todo multiple std in/out
      def attach[T](handler: Client.Handler[T]) =
        request(base.POST / id / "attach")(handler)

      // await -> wait
      def await =
        complete[Status](base.POST / id / "wait")

      def delete = Delete()

      // todo: octet stream
     /* def cp(resource: String) = new Client.Stream[Unit] {
        def apply[T](handler: Client.Handler[T]) =
          request(base.POST / id / "copy")(handler)
      }*/
    }

    def list = Containers()
    def create(image: String) = Create(ContainerConfig(image))
    /** alias for crete */
    def run = create _
    def get(id: String) = Container(id)
  }

  object images {
    private[this] def base = host / "images"

    case class Images(
      _all: Option[Boolean]                       = None,
      _filters: Option[Map[String, List[String]]] =  None)
      extends Client.Completion[List[tugboat.Image]] {
      def all = copy(_all = Some(true))
      def filters(fs: Map[String, List[String]]) = copy(_filters = Some(fs))
      // ( aka untagged ) for convenience
      def dangling(dang: Boolean) = filters(Map("dangling" -> (dang.toString :: Nil)))
      def apply[T](handler: Client.Handler[T]) = {
        request(base / "json" <<?
               (Map.empty[String, String]
                ++ _all.map(("all" -> _.toString)))
                ++ _filters.map("filters" -> json.str(_)))(handler)
      }
    }

    /** https://docs.docker.com/reference/api/docker_remote_api_v1.12/#create-an-image */
    case class Pull(
      _fromImage: String,
      _fromSrc: Option[String]   = None,
      _repo: Option[String]      = None,
      _tag: Option[String]       = None,
      _registry: Option[String]  = None)
      extends Client.Stream[tugboat.Pull.Output] {

      override protected def streamer = { f =>
        /** Like StringsByLine doesn't buffer. The images/create response
         *  returns chunked encoding by with a no explicit terminator for
         *  each chunk (typically a newline separator). We are being optimistic
         *  here in assuming that each logical stream chunk can be encoded
         *  in a single pack of body part bytes. I don't like this but
         *  until docker documents this better, this should work in most cases.
         */
        new Strings[Unit] {
          def onString(str: String) {
            f(implicitly[StreamRep[tugboat.Pull.Output]].map(str.trim))
          }
          def onCompleted = ()
        }
      }

      def fromImage(img: String) = copy(_fromImage = img)
      def fromSrc(src: String) = copy(_fromSrc = Some(src))
      def repo(r: String) = copy(_repo = Some(r))
      def tag(t: String) = copy(_tag = Some(t))
      def registry(r: String) = copy(_registry = Some(r))      
      def apply[T](handler: Client.Handler[T]) =
        request(base.POST / "create" <<?
               (Map("fromImage" -> _fromImage)
                ++ _fromSrc.map(("fromSrc" -> _))
                ++ _repo.map(("repo" -> _))
                ++ _tag.map(("tag" -> _))
                ++ _registry.map(("registry" -> _))))(handler)
    }

    case class Image(id: String)
      extends Client.Completion[Option[ImageDetails]] {

      // todo: stream rep
      // todo: x-registry-auth
      case class Push(
        _registry: Option[String] = None)
        extends Client.Stream[Unit] {
        def registry(reg: String) = copy(_registry = Some(reg))
        def apply[T](handler: Client.Handler[T]) =
          request(base.POST / id / "push" <:<
                 (Map.empty[String, String]
                  ++ authConfig.map(
                    ("X-Registry-Auth" -> _.headerValue)
                  )) <<?
                 (Map.empty[String, String]
                  ++ _registry.map(("registry" -> _))))(handler)
      }

      case class Tag(
        _repo: Option[String]   = None,
        _force: Option[Boolean] = None)
        extends Client.Completion[Unit] {
        def repo(r: String) = copy(_repo = Some(r))
        def force(f: Boolean) = copy(_force = Some(f))
        def apply[T](handler: Client.Handler[T]) =
          request(base.POST / id / "tag" <<?
                 (Map.empty[String, String]
                  ++ _repo.map(("repo" -> _))
                  ++ _force.map(("force" -> _.toString))))(handler)
      }

      // todo: stream rep
      case class Delete(
        _force: Option[Boolean]   = None,
        _noprune: Option[Boolean] = None)
        extends Client.Stream[Unit] {
        def force(f: Boolean) = copy(_force = Some(f))
        def noprune(np: Boolean) = copy(_noprune = Some(np))
        def apply[T](handler: Client.Handler[T]) =
          request(base.DELETE / id <<?
                 (Map.empty[String, String]
                  ++ _force.map(("force" -> _.toString))
                  ++ _noprune.map(("noprune" -> _.toString))))(handler)
      }

      def apply[T](handler: Client.Handler[T]) =
        request(base / id / "json")(handler)

      def history = complete[List[Event]](base / id / "history")

      // todo insert stream
      def insert(url: String, path: String) =
        stream[Unit](base.POST / id  / "insert" <<?
                     Map("url" -> url, "path" -> path))

      def push = Push()

      def tag = Tag()

      def delete = Delete()
    }

    case class Search(
      _term: Option[String] = None)
      extends Client.Completion[List[SearchResult]] {
      def term(t: String) = copy(_term = Some(t))
      def apply[T](handler: Client.Handler[T]) =
        request(base / "search" <<? _term.map(("term" -> _)))(handler)
    }

    /** https://docs.docker.com/reference/api/docker_remote_api_v1.12/#build-an-image-from-dockerfile-via-stdin */
    case class Build(
      path: File,
      _tag: Option[String]      = None,
      _q: Option[Boolean]       = None,
      _nocache: Option[Boolean] = None,
      _rm: Option[Boolean]      = None,
      _forcerm: Option[Boolean] = None)
      extends Client.Stream[tugboat.Build.Output] {
      lazy val tarfile = if (path.isDirectory) {
        Tar(path, TmpFile.create, path.getName, zip = true)
      } else path

      def tag(t: String) = copy(_tag = Some(t))
      def verbose(v: Boolean) = copy(_q = Some(!v))
      def nocache(n: Boolean) = copy(_nocache = Some(n))
      def rm(r: Boolean) = copy(_rm = Some(r))
      def forceRm(r: Boolean) = copy(_forcerm = Some(r))
      def apply[T](handler: Client.Handler[T]) =
        request((host.POST <:< Map(
                "Content-Type" -> "application/x-tar",
                "Content-Encoding" -> "gzip") ++ authConfig.map(
                  ("X-Registry-Auth" -> _.headerValue)
                 ) <<?
                (Map.empty[String, String]
                 ++ _tag.map(("t" -> _))
                 ++ _q.map(("q" -> _.toString))
                 ++ _nocache.map(("nocache" -> _.toString))
                 ++ _rm.map(("rm" -> _.toString))
                 ++ _forcerm.map(("forcerm" -> _.toString)))
                <<< tarfile) / "build")(handler)
    }

    def list = Images()
    // the api calls this create by the default client calls this pull
    // pull seems more `intention revealing` so let's use that
    def pull(image: String) = Pull(image)
    // but to avoid confustion let's alias it for those reading from the docs
    def create = pull _
    def get(id: String) = Image(id)
    def search = Search()
    /** if path is a directory, it will be bundled into a gzipped tar. otherwise we assume a tar file */
    def build(path: File) = Build(path)
  }
}
