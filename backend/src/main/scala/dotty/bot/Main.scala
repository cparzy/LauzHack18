package dotty.bot

import dotty.bot.model.Github.{AccessToken, IssueCommentEvent, PullRequestEvent}
import requests.RequestAuth
import upickle.default.read
import upickle.default.write
import requests.{RequestAuth, Session}

import Decorators._

import model.LauzHack.User

object Main extends cask.MainRoutes {

//  override def port: Int = 3338
  def GITHUB_USER = "allanrenucci"
  def GITHUB_TKN = "6c875951c64484cb9de5a2c0b994471deef54c3c"

  // LauzHack Github app
  def CLIENT_ID = "1eb8e00f3ac5bcfa3b42"
  def CLIENT_SECRET = "7243931f96aec1e7ba7c4638e65365bba78f3f44"

  override def debugMode: Boolean = true

  val ghSession = requests.Session(
    auth = new RequestAuth.Basic(GITHUB_USER, GITHUB_TKN)
  )


  class OAuth2(token: String) extends RequestAuth {
    def header = Some(s"token $token")
  }


  def ghUserSession(implicit user: User) = requests.Session(
    auth = new OAuth2(user.token)
  )

  @cask.get("/generateToken")
  def loggedIn(code: String) = {
    val response = requests.post(
      s"https://github.com/login/oauth/access_token?client_id=$CLIENT_ID&client_secret=$CLIENT_SECRET&code=$code",
      headers = Map("Accept" -> "application/json")
    )
    val signature = read[AccessToken](response.text)
    write[AccessToken](signature)
  }


  @cask.get("/")
  def root() = {
    implicit val user = User(token = "d8d87e3a68c43741fa2e5730534e952c8ae814f9")
    ghUserSession.get("https://api.github.com/user").text
  }

  @cask.get("/user")
  def user() = {
    ghSession.get("https://api.github.com/user").text
  }

  @cask.get("/rate")
  def rate() = {
    ghSession.get("https://api.github.com/rate_limit").text
  }

  @loggedIn()
  @cask.get("/test-logged-in")
  def loggedInTest()(user: User) = {
    Ok(s"Suce ${user.token}!")
  }

  initialize()
}
