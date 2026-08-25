package sgrv.e2e

import java.time.Duration
import org.openqa.selenium.By
import org.openqa.selenium.chrome.{ChromeDriver, ChromeOptions}
import org.openqa.selenium.support.ui.WebDriverWait

/** Exercises the real, running backend (started by the e2etest orchestration in build.sbt) through an actual Chrome
  * instance. The build supplies the shared configuration's `LOCAL_BASE_URL` as `E2E_BASE_URL`; the system property
  * remains available as an explicit override.
  */
class HomePageE2ESuite extends munit.FunSuite:
  private val baseUrl = sys.props
    .get("e2e.baseUrl")
    .orElse(sys.env.get("E2E_BASE_URL"))
    .getOrElse(throw new IllegalStateException("E2E_BASE_URL is not configured"))
  private var driver: ChromeDriver = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    val options = new ChromeOptions()
    options.addArguments("--headless=new")
    driver = new ChromeDriver(options)

  override def afterAll(): Unit =
    if driver != null then driver.quit()

  // The name is no longer compiled into the frontend bundle (see CardText.scala): it's fetched from
  // GET /personalparticulars at runtime, so it isn't present in the DOM immediately after the page loads, and
  // its real value is personal data that has no business being hardcoded into a checked-in test either way.
  // Waiting for the element's text to become non-empty proves the fetch-and-render pipeline actually worked,
  // without asserting on (or needing to know) the configured name itself.
  test("the home page renders the business card"):
    driver.get(baseUrl)
    val wait = WebDriverWait(driver, Duration.ofSeconds(10))
    val name = wait.until { (driver: org.openqa.selenium.WebDriver) =>
      val element = driver.findElement(By.cssSelector(".card-name"))
      if element.getText.trim.nonEmpty then element else null
    }

    assert(name.getText.trim.nonEmpty)
