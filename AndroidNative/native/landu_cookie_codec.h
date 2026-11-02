// Shared by the production Cookie bridge and the arm64 codec probe.
#ifndef CHROME_BROWSER_ANDROID_COOKIES_LANDU_COOKIE_CODEC_H_
#define CHROME_BROWSER_ANDROID_COOKIES_LANDU_COOKIE_CODEC_H_
#include <cmath>
#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include "base/json/json_reader.h"
#include "base/json/json_writer.h"
#include "base/strings/string_number_conversions.h"
#include "base/strings/string_util.h"
#include "base/time/time.h"
#include "base/values.h"
#include "net/cookies/canonical_cookie.h"
#include "net/cookies/cookie_partition_key.h"
#include "net/cookies/cookie_util.h"
namespace landu_cookie_transfer {
constexpr size_t kMaxBytes = 500000;
constexpr size_t kMaxCookies = 5000;

inline const char* SameSiteName(net::CookieSameSite value) {
  switch (value) {
    case net::CookieSameSite::UNSPECIFIED: return "unspecified";
    case net::CookieSameSite::NO_RESTRICTION: return "no_restriction";
    case net::CookieSameSite::LAX_MODE: return "lax";
    case net::CookieSameSite::STRICT_MODE: return "strict";
  }
  return "unspecified";
}

// Preserve microseconds without passing 64-bit Windows timestamps through a
// JSON double. Ordinary external Cookie arrays need not contain this metadata.
inline std::string TimeString(base::Time value) {
  return base::NumberToString(value.ToDeltaSinceWindowsEpoch().InMicroseconds());
}

inline bool ReadTime(const base::DictValue& dict, const char* key, base::Time* out) {
  const std::string* text = dict.FindString(key);
  int64_t micros = 0;
  if (!text || !base::StringToInt64(*text, &micros) || micros < 0 ||
      micros > 900000000000000000LL) return false;
  *out = base::Time::FromDeltaSinceWindowsEpoch(base::Microseconds(micros));
  return true;
}

inline std::optional<base::DictValue> Encode(const net::CanonicalCookie& cookie) {
  auto partition = net::CookiePartitionKey::Serialize(cookie.PartitionKey());
  if (!partition.has_value()) return std::nullopt;
  base::DictValue value;
  value.Set("name", cookie.Name());
  value.Set("value", cookie.Value());
  value.Set("domain", cookie.Domain());
  value.Set("path", cookie.Path());
  value.Set("hostOnly", !cookie.IsDomainCookie());
  value.Set("secure", cookie.SecureAttribute());
  value.Set("httpOnly", cookie.IsHttpOnly());
  value.Set("session", !cookie.IsPersistent());
  value.Set("sameSite", SameSiteName(cookie.SameSite()));
  if (cookie.IsPersistent())
    value.Set("expirationDate", cookie.ExpiryDate().InSecondsFSinceUnixEpoch());
  if (cookie.IsPartitioned()) {
    base::DictValue key;
    key.Set("topLevelSite", partition->TopLevelSite());
    key.Set("hasCrossSiteAncestor", partition->has_cross_site_ancestor());
    value.Set("partitionKey", std::move(key));
  }
  base::DictValue metadata;
  metadata.Set("format", 1);
  metadata.Set("creation", TimeString(cookie.CreationDate()));
  metadata.Set("expiration", TimeString(cookie.ExpiryDate()));
  metadata.Set("lastAccess", TimeString(cookie.LastAccessDate()));
  metadata.Set("lastUpdate", TimeString(cookie.LastUpdateDate()));
  metadata.Set("priority", static_cast<int>(cookie.Priority()));
  metadata.Set("sourceScheme", static_cast<int>(cookie.SourceScheme()));
  metadata.Set("sourcePort", cookie.SourcePort());
  metadata.Set("sourceType", static_cast<int>(cookie.SourceType()));
  value.Set("_landu", std::move(metadata));
  return value;
}

inline std::unique_ptr<net::CanonicalCookie> DecodeCanonical(const base::DictValue& value) {
  const std::string* name = value.FindString("name");
  const std::string* contents = value.FindString("value");
  const std::string* domain = value.FindString("domain");
  const std::string* path = value.FindString("path");
  if (!name || !contents || !domain || domain->empty() || !path ||
      path->empty() || (*path)[0] != '/' || name->size() + contents->size() > 4096)
    return nullptr;
  auto secure = value.FindBool("secure");
  auto http_only = value.FindBool("httpOnly");
  if (!secure || !http_only) return nullptr;
  bool host_only = value.FindBool("hostOnly").value_or((*domain)[0] != '.');
  if (value.contains("hostOnly") && !value.FindBool("hostOnly")) return nullptr;
  if (host_only != ((*domain)[0] != '.')) return nullptr;
  net::CookieSameSite same_site = net::CookieSameSite::UNSPECIFIED;
  if (const auto* same = value.FindString("sameSite")) {
    if (*same == "no_restriction" || *same == "none")
      same_site = net::CookieSameSite::NO_RESTRICTION;
    else if (*same == "lax") same_site = net::CookieSameSite::LAX_MODE;
    else if (*same == "strict") same_site = net::CookieSameSite::STRICT_MODE;
    else if (*same != "unspecified") return nullptr;
  } else if (value.contains("sameSite")) return nullptr;
  std::optional<net::CookiePartitionKey> partition;
  if (value.contains("partitionKey")) {
    const auto* key = value.FindDict("partitionKey");
    if (!key) return nullptr;
    const auto* site = key->FindString("topLevelSite");
    auto ancestor = key->FindBool("hasCrossSiteAncestor");
    if (!site || !ancestor) return nullptr;
    auto decoded = net::CookiePartitionKey::FromStorage(*site, *ancestor);
    if (!decoded.has_value() || !decoded.value()) return nullptr;
    partition = std::move(decoded.value());
  }
  base::Time now = base::Time::Now();
  base::Time expiration;
  bool session = value.FindBool("session").value_or(!value.contains("expirationDate"));
  if (value.contains("session") && !value.FindBool("session")) return nullptr;
  if (session && value.contains("expirationDate")) return nullptr;
  if (!session) {
    auto seconds = value.FindDouble("expirationDate");
    if (!seconds || !std::isfinite(*seconds) || *seconds <= now.InSecondsFSinceUnixEpoch() ||
        *seconds > 253402300799.0) return nullptr;
    expiration = base::Time::FromSecondsSinceUnixEpoch(*seconds);
  }
  GURL source = net::cookie_util::CookieDomainAndPathToURL(
      *domain, *path, *secure ? net::CookieSourceScheme::kSecure
                            : net::CookieSourceScheme::kNonSecure);
  if (const auto* url = value.FindString("url")) source = GURL(*url);
  if (!source.is_valid() || !source.SchemeIsHTTPOrHTTPS()) return nullptr;
  auto cookie = net::CanonicalCookie::CreateSanitizedCookie(
      source, *name, *contents, host_only ? std::string() : *domain, *path,
      now, expiration, now, *secure, *http_only, same_site,
      net::COOKIE_PRIORITY_MEDIUM, partition, nullptr);
  // Sanitization must not silently rewrite the selected identity or value.
  if (!cookie || cookie->Name() != *name || cookie->Value() != *contents ||
      cookie->Domain() != *domain || cookie->Path() != *path) return nullptr;
  if (value.contains("_landu")) {
    const auto* metadata = value.FindDict("_landu");
    if (!metadata || metadata->FindInt("format") != 1) return nullptr;
    base::Time creation, exact_expiration, access, update;
    if (!ReadTime(*metadata, "creation", &creation) ||
        !ReadTime(*metadata, "expiration", &exact_expiration) ||
        !ReadTime(*metadata, "lastAccess", &access) ||
        !ReadTime(*metadata, "lastUpdate", &update)) return nullptr;
    auto priority = metadata->FindInt("priority");
    auto scheme = metadata->FindInt("sourceScheme");
    auto port = metadata->FindInt("sourcePort");
    auto type = metadata->FindInt("sourceType");
    if (!priority || *priority < 0 || *priority > 2 ||
        !scheme || *scheme < 0 || *scheme > 2 ||
        !port || *port < -1 || *port > 65535 ||
        !type || *type < 1 || *type > 3 || creation.is_null() ||
        session != exact_expiration.is_null() ||
        (!session && (exact_expiration <= now ||
          std::abs((exact_expiration - expiration).InMicroseconds()) > 2))) return nullptr;
    cookie = net::CanonicalCookie::FromStorage(
        *name, *contents, *domain, *path, creation, exact_expiration, access, update,
        *secure, *http_only, same_site, static_cast<net::CookiePriority>(*priority),
        std::move(partition), static_cast<net::CookieSourceScheme>(*scheme), *port,
        static_cast<net::CookieSourceType>(*type),
        net::CanonicalCookieFromStorageCallSite::kAndroidCookiesFetcherRestoreUtil);
    if (!cookie || !cookie->IsCanonical()) return nullptr;
  }
  return cookie;
}

inline GURL SourceUrlFor(const net::CanonicalCookie& cookie) {
  GURL source = net::cookie_util::CookieDomainAndPathToURL(
      cookie.Domain(), cookie.Path(), cookie.SecureAttribute() ||
          cookie.SourceScheme() == net::CookieSourceScheme::kSecure);
  if (cookie.SourcePort() >= 0) {
    std::string port = base::NumberToString(cookie.SourcePort());
    GURL::Replacements replacements;
    replacements.SetPortStr(port);
    source = source.ReplaceComponents(replacements);
  }
  return source;
}

// Match the desktop import/export field names without silently discarding an
// expires value. Malformed types/conflicting session flags remain explicit errors.
inline std::unique_ptr<net::CanonicalCookie> Decode(const base::DictValue& input) {
  if (input.contains("_landu")) return DecodeCanonical(input);
  base::DictValue value = input.Clone();
  if (!value.contains("path")) value.Set("path", "/");
  if (!value.contains("secure")) value.Set("secure", false);
  if (!value.contains("httpOnly") || value.Find("httpOnly")->is_none()) {
    if (const auto* alias = value.Find("http_only")) value.Set("httpOnly", alias->Clone());
    else value.Set("httpOnly", false);
  }
  if (const auto* source = value.Find("url"); source && !source->is_none()) {
    const std::string* text = source->GetIfString();
    if (!text) return nullptr;
    GURL url(*text);
    if (!url.is_valid() || !url.SchemeIsHTTPOrHTTPS() || url.has_username() || url.has_password()) return nullptr;
    value.Set("domain", url.host());
    value.Set("hostOnly", true);
  }
  const base::Value* site = input.Find("sameSite");
  if (!site || site->is_none()) site = input.Find("same_site");
  if (site && !site->is_none()) {
    const std::string* text = site->GetIfString();
    if (!text) return nullptr;
    value.Set("sameSite", base::ToLowerASCII(*text));
  } else value.Remove("sameSite");
  const base::Value* expiry = input.Find("expires");
  if (!expiry || expiry->is_none()) expiry = input.Find("expirationDate");
  if (!expiry || expiry->is_none()) expiry = input.Find("expiration_date");
  if (expiry && !expiry->is_none()) {
    double seconds = 0;
    if (expiry->is_double() || expiry->is_int()) seconds = expiry->GetDouble();
    else if (expiry->is_string()) {
      std::string text(base::TrimWhitespaceASCII(expiry->GetString(), base::TRIM_ALL));
      if (!text.empty() && !base::StringToDouble(text, &seconds)) return nullptr;
    } else return nullptr;
    if (!std::isfinite(seconds)) return nullptr;
    if (seconds > 1e12) seconds /= 1000;
    if (seconds > 0) value.Set("expirationDate", seconds);
    else value.Remove("expirationDate");
  } else value.Remove("expirationDate");
  return DecodeCanonical(value);
}


}  // namespace landu_cookie_transfer
#endif
