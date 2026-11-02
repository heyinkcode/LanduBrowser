// Standalone arm64 probe of the production codec; no Chrome UI or real profiles.
#include <iostream>
#include <utility>
#include "base/at_exit.h"
#include "base/command_line.h"
#include "base/feature_list.h"
#include "chrome/browser/android/cookies/landu_cookie_codec.h"

namespace {
int assertions = 0;
int failures = 0;
void Check(bool condition, int line) {
  ++assertions;
  if (!condition) { ++failures; std::cerr << "Cookie codec check failed at " << line << '\n'; }
}
#define VERIFY(condition) Check(static_cast<bool>(condition), __LINE__)
base::DictValue Example() {
  base::DictValue value;
  value.Set("name", "landu_own_test");
  value.Set("value", "synthetic-only");
  value.Set("domain", "example.com");
  value.Set("path", "/account");
  value.Set("hostOnly", true);
  value.Set("secure", true);
  value.Set("httpOnly", true);
  value.Set("session", true);
  value.Set("sameSite", "strict");
  return value;
}
void RoundTrip(base::DictValue value) {
  auto cookie = landu_cookie_transfer::Decode(value);
  VERIFY(cookie);
  if (!cookie) return;
  auto exported = landu_cookie_transfer::Encode(*cookie);
  VERIFY(exported);
  if (!exported) return;
  auto restored = landu_cookie_transfer::Decode(*exported);
  VERIFY(restored);
  if (!restored) return;
  VERIFY(cookie->IsEquivalent(*restored));
  VERIFY(cookie->Value() == restored->Value());
  VERIFY(cookie->IsHttpOnly() == restored->IsHttpOnly());
  VERIFY(cookie->SecureAttribute() == restored->SecureAttribute());
  VERIFY(cookie->SameSite() == restored->SameSite());
  VERIFY(cookie->CreationDate() == restored->CreationDate());
  VERIFY(cookie->ExpiryDate() == restored->ExpiryDate());
  VERIFY(cookie->LastAccessDate() == restored->LastAccessDate());
  VERIFY(cookie->LastUpdateDate() == restored->LastUpdateDate());
  VERIFY(cookie->PartitionKey() == restored->PartitionKey());
  VERIFY(cookie->SourceScheme() == restored->SourceScheme());
  VERIFY(cookie->SourcePort() == restored->SourcePort());
  VERIFY(cookie->SourceType() == restored->SourceType());
  VERIFY(cookie->Priority() == restored->Priority());
}
}

int main(int argc, char** argv) {
  base::AtExitManager exit;
  base::CommandLine::Init(argc, argv);
  base::FeatureList::SetInstance(std::make_unique<base::FeatureList>());
  RoundTrip(Example());
  auto persistent = Example();
  persistent.Set("session", false);
  persistent.Set("expirationDate", (base::Time::Now() + base::Days(2)).InSecondsFSinceUnixEpoch());
  RoundTrip(persistent.Clone());
  auto domain = Example();
  domain.Set("domain", ".example.com"); domain.Set("hostOnly", false);
  domain.Set("sameSite", "no_restriction");
  RoundTrip(domain.Clone());
  auto partitioned = Example();
  base::DictValue partition;
  partition.Set("topLevelSite", "https://other.example");
  partition.Set("hasCrossSiteAncestor", false);
  partitioned.Set("partitionKey", partition.Clone());
  RoundTrip(partitioned.Clone());
  partition.Set("hasCrossSiteAncestor", true);
  partitioned.Set("partitionKey", partition.Clone());
  RoundTrip(partitioned.Clone());
  auto local = Example();
  local.Set("domain", "127.0.0.1"); local.Set("secure", false); local.Set("sameSite", "lax");
  RoundTrip(local.Clone());
  auto no_name = Example(); no_name.Set("name", "");
  RoundTrip(no_name.Clone());
  // Chromium intentionally accepts interior spaces in Cookie names. Preserve
  // that legitimate upstream grammar; leading/trailing whitespace still fails.
  auto spaced_name = Example(); spaced_name.Set("name", "valid interior space");
  RoundTrip(spaced_name.Clone());
  VERIFY(landu_cookie_transfer::kMaxCookies == 5000);
  for (const char* field : {"name", "value", "domain"}) {
    auto invalid = Example(); invalid.Remove(field);
    VERIFY(!landu_cookie_transfer::Decode(invalid));
  }
  for (const char* field : {"name", "value", "domain", "path", "secure", "httpOnly"}) {
    auto invalid = Example(); invalid.Set(field, 7);
    VERIFY(!landu_cookie_transfer::Decode(invalid));
  }
  for (const char* field : {"path", "secure", "httpOnly"}) {
    auto defaults = Example(); defaults.Remove(field);
    RoundTrip(defaults.Clone());
  }
  auto desktop = Example();
  desktop.Set("sameSite", "Lax");
  desktop.Set("expires", (base::Time::Now() + base::Days(2)).InSecondsFSinceUnixEpoch());
  desktop.Remove("session");
  auto from_desktop = landu_cookie_transfer::Decode(desktop);
  VERIFY(from_desktop && from_desktop->IsPersistent());
  VERIFY(from_desktop && from_desktop->SameSite() == net::CookieSameSite::LAX_MODE);
  RoundTrip(desktop.Clone());
  auto milliseconds = desktop.Clone();
  milliseconds.Set("expiration_date", *milliseconds.FindDouble("expires") * 1000);
  milliseconds.Remove("expires");
  auto from_milliseconds = landu_cookie_transfer::Decode(milliseconds);
  VERIFY(from_milliseconds && from_desktop && from_milliseconds->ExpiryDate() == from_desktop->ExpiryDate());
  RoundTrip(milliseconds.Clone());
  auto url_cookie = milliseconds.Clone();
  url_cookie.Remove("domain"); url_cookie.Remove("path"); url_cookie.Remove("secure");
  url_cookie.Remove("sameSite"); url_cookie.Set("same_site", "Strict");
  url_cookie.Remove("httpOnly"); url_cookie.Set("http_only", true);
  url_cookie.Set("url", "https://example.com:8443/account");
  auto from_url = landu_cookie_transfer::Decode(url_cookie);
  VERIFY(from_url && from_url->Domain() == "example.com" && from_url->IsHostCookie());
  VERIFY(from_url && from_url->SourceScheme() == net::CookieSourceScheme::kSecure && from_url->SourcePort() == 8443);
  VERIFY(from_url && from_url->IsHttpOnly() && from_url->Path() == "/");
  RoundTrip(url_cookie.Clone());
  auto session_export = desktop.Clone(); session_export.Set("expires", -1.0);
  auto from_session = landu_cookie_transfer::Decode(session_export);
  VERIFY(from_session && !from_session->IsPersistent());
  RoundTrip(session_export.Clone());
  auto numeric_expiry = desktop.Clone();
  numeric_expiry.Set("expires", base::NumberToString(*numeric_expiry.FindDouble("expires")));
  auto from_numeric_expiry = landu_cookie_transfer::Decode(numeric_expiry);
  VERIFY(from_numeric_expiry && from_desktop && from_numeric_expiry->ExpiryDate() == from_desktop->ExpiryDate());
  auto invalid_alias = desktop.Clone(); invalid_alias.Set("expires", "not a timestamp");
  VERIFY(!landu_cookie_transfer::Decode(invalid_alias));
  for (const char* url : {"not a url", "file:///tmp/own", "https://user:pass@example.com/"}) {
    auto invalid_url = url_cookie.Clone(); invalid_url.Set("url", url);
    VERIFY(!landu_cookie_transfer::Decode(invalid_url));
  }
  for (const char* bad : {"", "relative", "/a;b", "/a\r\nb"}) {
    auto invalid = Example(); invalid.Set("path", bad);
    VERIFY(!landu_cookie_transfer::Decode(invalid));
  }
  for (const char* bad : {" leading", "trailing ", "name=bad", "bad\r\n", "bad;name"}) {
    auto invalid = Example(); invalid.Set("name", bad);
    VERIFY(!landu_cookie_transfer::Decode(invalid));
  }
  for (const char* bad : {"bad\r\n", "bad;value"}) {
    auto invalid = Example(); invalid.Set("value", bad);
    VERIFY(!landu_cookie_transfer::Decode(invalid));
  }
  for (const char* bad : {"", "https://example.com", "example.com:443", "example.com/path"}) {
    auto invalid = Example(); invalid.Set("domain", bad);
    VERIFY(!landu_cookie_transfer::Decode(invalid));
  }
  auto invalid = Example(); invalid.Set("value", std::string(4097, 'x'));
  VERIFY(!landu_cookie_transfer::Decode(invalid));
  invalid = Example(); invalid.Set("hostOnly", false);
  VERIFY(!landu_cookie_transfer::Decode(invalid));
  invalid = domain.Clone(); invalid.Set("hostOnly", true);
  VERIFY(!landu_cookie_transfer::Decode(invalid));
  invalid = Example(); invalid.Set("hostOnly", "true");
  VERIFY(!landu_cookie_transfer::Decode(invalid));
  invalid = Example(); invalid.Set("sameSite", "unknown");
  VERIFY(!landu_cookie_transfer::Decode(invalid));
  invalid = Example(); invalid.Set("sameSite", false);
  VERIFY(!landu_cookie_transfer::Decode(invalid));
  invalid = Example(); invalid.Set("session", "true");
  VERIFY(!landu_cookie_transfer::Decode(invalid));
  invalid = Example(); invalid.Set("expirationDate", 10.0);
  VERIFY(!landu_cookie_transfer::Decode(invalid));
  invalid = persistent.Clone(); invalid.Set("expirationDate", 10.0);
  VERIFY(!landu_cookie_transfer::Decode(invalid));
  invalid = persistent.Clone(); invalid.Remove("expirationDate");
  VERIFY(!landu_cookie_transfer::Decode(invalid));
  invalid = persistent.Clone(); invalid.Set("expirationDate", "1234");
  VERIFY(!landu_cookie_transfer::Decode(invalid));
  invalid = partitioned.Clone(); invalid.Set("partitionKey", false);
  VERIFY(!landu_cookie_transfer::Decode(invalid));
  partition.Remove("hasCrossSiteAncestor");
  invalid = Example(); invalid.Set("partitionKey", partition.Clone());
  VERIFY(!landu_cookie_transfer::Decode(invalid));
  partition.Set("hasCrossSiteAncestor", false); partition.Set("topLevelSite", "not a site");
  invalid = Example(); invalid.Set("partitionKey", partition.Clone());
  VERIFY(!landu_cookie_transfer::Decode(invalid));
  auto original = landu_cookie_transfer::Decode(Example());
  VERIFY(original);
  if (original) {
    auto encoded = landu_cookie_transfer::Encode(*original);
    VERIFY(encoded);
    if (encoded) {
      for (int priority : {0, 2}) {
        auto custom = encoded->Clone();
        custom.FindDict("_landu")->Set("priority", priority);
        custom.FindDict("_landu")->Set("sourcePort", 8443);
        custom.FindDict("_landu")->Set("sourceType", priority == 0 ? 1 : 2);
        RoundTrip(custom.Clone());
        auto cookie = landu_cookie_transfer::Decode(custom);
        VERIFY(cookie);
        if (cookie) {
          GURL source = landu_cookie_transfer::SourceUrlFor(*cookie);
          VERIFY(source.SchemeIs("https"));
          VERIFY(source.EffectiveIntPort() == 8443);
          VERIFY(source.host() == "example.com");
        }
      }
      for (const char* field : {"creation", "expiration", "lastAccess", "lastUpdate", "priority", "sourceScheme", "sourcePort", "sourceType", "format"}) {
        auto damaged = encoded->Clone(); damaged.FindDict("_landu")->Remove(field);
        VERIFY(!landu_cookie_transfer::Decode(damaged));
      }
      auto damaged = encoded->Clone(); damaged.FindDict("_landu")->Set("creation", "9223372036854775807");
      VERIFY(!landu_cookie_transfer::Decode(damaged));
      damaged = encoded->Clone(); damaged.FindDict("_landu")->Set("sourceType", 0);
      VERIFY(!landu_cookie_transfer::Decode(damaged));
      damaged = encoded->Clone(); damaged.FindDict("_landu")->Set("sourceScheme", 3);
      VERIFY(!landu_cookie_transfer::Decode(damaged));
      damaged = encoded->Clone(); damaged.FindDict("_landu")->Set("sourcePort", 65536);
      VERIFY(!landu_cookie_transfer::Decode(damaged));
      damaged = encoded->Clone(); damaged.FindDict("_landu")->Set("priority", 3);
      VERIFY(!landu_cookie_transfer::Decode(damaged));
    }
  }
  std::cout << "{\"suite\":\"LanduCookieCodecArm64\",\"assertions\":" << assertions
            << ",\"failures\":" << failures << ",\"passed\":" << (failures == 0 ? "true" : "false") << "}\n";
  return failures == 0 ? 0 : 1;
}
