package dotty.bot

import cask.model.Response
import dotty.bot.model.{Github, LauzHack}
import dotty.bot.model.Github.{AccessToken, Repositories, Repository, UserUpdate, PullRequests}
import dotty.bot.model.LauzHack.User
import requests.RequestAuth
import ujson.Js
import upickle.default.{read, write}

object Main extends cask.MainRoutes {
  // LauzHack Github app
  def CLIENT_ID = "1eb8e00f3ac5bcfa3b42"
  def CLIENT_SECRET = "7243931f96aec1e7ba7c4638e65365bba78f3f44"

  def GITHUB_USER = "allanrenucci"
  def GITHUB_TKN = "6c875951c64484cb9de5a2c0b994471deef54c3c"

  override def debugMode: Boolean = true

  // val ghSession = requests.Session(
  //   auth = new RequestAuth.Basic(GITHUB_USER, GITHUB_TKN)
  // )

  class OAuth2(token: String) extends RequestAuth {
    def header = Some(s"token $token")
  }

  def ghUserSession(token: String) = requests.Session(
    auth = new OAuth2(token)
  )

  /** Build the GitHub API url */
  private def ghAPI(path: String) = "https://api.github.com" + path

  @cask.get("/generateToken")
  def generateToken(code: String): Response = {
    val response = requests.post(
      s"https://github.com/login/oauth/access_token?client_id=$CLIENT_ID&client_secret=$CLIENT_SECRET&code=$code",
      headers = Map("Accept" -> "application/json")
    )

    if (response.is2xx) {
      val signature = read[AccessToken](response.text)
      val user = read[Github.User](
        ghUserSession(signature.access_token).get(ghAPI("/user")).text)
      DB.addUser(signature.access_token, user.login)
      Ok(write[AccessToken](signature))
    } else {
      BadRequest(response.statusMessage)
    }
  }

  @cask.get("/timeline")
  def timeline(token: String): cask.Response = {
    val user = DB.getUser(token)
    val topics = user.topics.map(s => "topic:" + s).mkString("+")
    val languages = user.languages.map(s => "language:" + s).mkString("+")
    var repos: Seq[Github.Repository] = List.empty
    if (!topics.isEmpty || !languages.isEmpty) {
      var query = topics
      if (!query.isEmpty) {
        query += "+" + languages
      } else {
        query = languages
      }

      val response = ghUserSession(token).get(
        ghAPI(s"/search/repositories?q=$query&sort=stars&order=desc"),
        headers = Map ("Accept" -> "application/vnd.github.mercy-preview+json"))
      if (!response.is2xx) {
        BadRequest(response.statusMessage)
      }
      repos = read[Repositories](response.text).items
    }
    Ok(timelineToJson(token, repos))
  }

  @cask.get("/project")
  def project(token: String, owner: String, repo: String): Response = {
    val response = ghUserSession(token).get(
      ghAPI(s"/repos/$owner/$repo"),
      headers = Map ("Accept" -> "application/vnd.github.mercy-preview+json")
    )
    val issues = getIssues(token, owner, repo)
    val user = DB.getUser(token)
    if (response.is2xx) {
      val repo = read[Github.Repository](response.text)
      val cleaned = Js.Obj(
        "full_name" -> Js.Str(repo.full_name),
        "name" -> Js.Str(repo.name),
        "url" -> Js.Str(repo.html_url),
        "description" -> Js.Str(repo.description),
        "followed" ->  Js.Bool(user.followedRepos.contains(repo.full_name)),
        "owner" -> Js.Str(repo.owner.login),
        "topics" -> repo.topics,
        "issues" -> issues
      )
      Ok(ujson.write(cleaned))
    } else {
      BadRequest(response.statusMessage)
    }
  }

  // @cask.get("/")
  // def root(): String = {
  //   ghSession.get(ghAPI("/user")).text
  // }

  @cask.get("/test-logged-in")
  def loggedInTest(param: String)(user: User): Response = {
    Ok(s"Suce ${user.token} $param!")
  }

  @cask.get("/profile")
  def profile(token: String): cask.Response = {
    val user = DB.getUser(token)
    val response = ghUserSession(token).get(ghAPI("/user"))
    val ghUser = read[Github.User](response.text)
    val trophyCount = mergedPRs(user)

    val profile = Js.Obj(
      "name" -> Js.Str(ghUser.login),
      "picture" -> Js.Str(ghUser.avatar_url),
      "topics" -> user.topics,
      "languages" -> user.languages,
      "trophies" -> Js.Arr(trophyCount._1, trophyCount._2, trophyCount._3)
    )
    Ok(ujson.write(profile))
  }

  @cask.get("/follow")
  def follow(token: String, owner: String, repo: String): Response = {
    val user = DB.getUser(token)
    val fullName = s"$owner/$repo"

    // Adding repo to cache
    val response = ghUserSession(token).get(ghAPI(s"/repos/$owner/$repo")).text
    println(response)
    val ghRepo = read[Repository](response)
    val lhRepo = LauzHack.Repository(
      name = repo,
      owner = owner,
      full_name = fullName,
      description = ghRepo.description,
      html_url = ghRepo.html_url,
      topics = ghRepo.topics
    )
    val repos = DB.repositories
    repos += (fullName -> lhRepo)

    // Adding repo to followed repo
    user.followedRepos += fullName

    val toSend = user.followedRepos.toList.map(repos(_))
    Ok(write[List[LauzHack.Repository]](toSend))
  }

  @cask.get("/unfollow")
  def unfollow(token: String, owner: String, repo: String): Response = {
    val user = DB.getUser(token)
    val fullName = s"$owner/$repo"
    user.followedRepos -= fullName
    val toSend = user.followedRepos.toList.map(DB.repositories(_))
    Ok(write[List[LauzHack.Repository]](toSend))
  }

  @cask.get("/debug")
  def debug(): Response = {
    Ok(DB.dump())
  }

  @cask.post("/tags")
  def tags(request: cask.Request): Unit = {
    val data = new String(request.readAllBytes())
    val updatedUser = read[UserUpdate](data)
    val user = DB.getUser(updatedUser.token)
    user.languages.clear()
    user.languages ++= updatedUser.languages
    user.topics.clear()
    user.topics ++= updatedUser.topics
  }


  private def mergedPRs(user: User): (Int, Int, Int) = {
    val params = List(
      "type:pr",
      s"author:${user.login}",
      "is:merged"
    )
    val query = params.mkString("+")
    val response = ghUserSession(user.token).get(ghAPI("/search/issues?q=" + query)).text
    println(response)
    val counts = read[PullRequests](response).items.map(_.repository_url).groupBy(identity).mapValues(_.size).values
    println(counts)
    val bronze = counts.count(i => i >= 5 && i < 10)
    val argent = counts.count(i => i >= 10  && i < 20)
    val gold = counts.count(i => i >= 20)

    (bronze, argent, gold)
  }


  @cask.get("/notifications")
  def notifications(token: String): Response = {
    val user = DB.getUser(token)
    val prCount = mergedPRs(user)

    var newPR = false
    var newTropy = "none"

    if (prCount != user.prCount) {
      newPR = true
      if (user.prCount._3 < prCount._3)
        newTropy = "gold"
      else if (user.prCount._2 < prCount._2)
        newTropy = "silver"
      else if (user.prCount._1 < prCount._1)
        newTropy = "bronze"

      user.prCount = prCount
    }

    val response = ujson.write(
      Js.Obj(
        "pr_merged"  -> newPR,
        "new_trophy" -> newTropy
      )
    )
    Ok(response)
  }


  private def getIssues(token: String, owner: String, repo: String) = {
    val response = ghUserSession(token).get(
      ghAPI(s"/repos/$owner/$repo/issues"),
      params = Map(
        "labels" -> "help wanted"
      )
    )

    if (response.is2xx) {
      val issues = read[List[Github.Issue]](response.text)
      val cleaned = issues.map { i =>
        Js.Obj(
          "number" -> Js.Num(i.number),
          "title" -> Js.Str(i.title),
          "url" -> Js.Str(i.html_url),
          "created" -> Js.Str(i.created_at),
          "user" -> Js.Str(i.user.login)
        )
      }
      cleaned
    }
    else
      List.empty
  }

  private def timelineToJson(token: String, repos: Seq[Repository]): String = {
    val recommendedRepo = repos.map(i => LauzHack.Repository(i.name, i.owner.login, i.full_name, i.description, i.html_url, i.topics))
    val user = DB.getUser(token)
    val followed = user.followedRepos.map(r => DB.repositories(r))
    val set = followed.map(i => i.full_name).toSet
    val recommended = recommendedRepo.filter(p => !set.contains(p.full_name))
    val jsonRecommended = recommended.map(i => {
      Js.Obj(
        "full_name" -> Js.Str(i.full_name),
        "name" -> Js.Str(i.name),
        "description" -> Js.Str(i.description),
        "owner" -> Js.Str(i.owner)
      )
    })
    val jsonFollowed = followed.map(rep => {
      Js.Obj(
        "full_name"  -> Js.Str(rep.full_name),
        "name"   -> Js.Str(rep.name),
        "description" -> Js.Str(rep.description),
        "owner" -> Js.Str(rep.owner)
      )
    })

    val res = Js.Obj(
      "recommended_projects" -> jsonRecommended,
      "followed_projects" -> jsonFollowed
    )
    ujson.write(res)
  }

  initialize()

  DB.addUser("d8d87e3a68c43741fa2e5730534e952c8ae814f9", "MikaelMorales")
}
