// Landu regular-profile Cookie transfer. Included by cookies_fetcher_util.cc.
// No paths, credentials, Cookie values or JSON are written to diagnostic logs.
#include <memory>
#include <set>
#include <utility>

#include "base/android/callback_android.h"
#include "chrome/browser/android/cookies/landu_cookie_codec.h"
#include "base/json/json_writer.h"
#include "base/strings/string_number_conversions.h"
#include "base/timer/timer.h"
#include "mojo/public/cpp/bindings/callback_helpers.h"
#include "net/cookies/cookie_access_result.h"
#include "net/cookies/cookie_options.h"

namespace landu_cookie_transfer {

// All entries are validated before the first CookieManager mutation. This is
// additive/upsert import, never a whole-profile clear. Individual native writes
// are not transactional: report partial/unknown results instead of claiming rollback.
class Operation : public std::enable_shared_from_this<Operation> {
 public:
  Operation(Profile* profile, const base::android::JavaRef<jobject>& callback);
  ~Operation();

  void Start(bool importing, const std::string& json) {
    timer_.Start(FROM_HERE, base::Seconds(25),
                 base::BindOnce(&Operation::Failed, shared_from_this(), "timeout_unknown"));
    importing_ = importing;
    if (!Manager()) { Failed("profile_unavailable"); return; }
    if (!importing) { Fetch(); return; }
    if (json.empty() || json.size() > kMaxBytes) { Failed("input_size"); return; }
    auto parsed = base::JSONReader::Read(json, base::JSON_PARSE_RFC, 16);
    const base::ListValue* list = parsed ? parsed->GetIfList() : nullptr;
    if (!list || list->size() > kMaxCookies) { Failed("input_format"); return; }
    std::set<net::CanonicalCookie> seen;
    for (const auto& entry : *list) {
      const auto* dict = entry.GetIfDict();
      auto cookie = dict ? Decode(*dict) : nullptr;
      if (!cookie || !seen.insert(*cookie).second) { Failed("invalid_cookie"); return; }
      expected_.push_back(std::move(*cookie));
    }
    SetNext();
  }

 private:
  network::mojom::CookieManager* Manager() {
    return !finished_ && profile_ && !profile_->IsOffTheRecord()
        ? cookie_fetcher_restore_util::GetCookieServiceClient(profile_.get()) : nullptr;
  }

  template <typename Callback>
  auto Guard(Callback callback) {
    return mojo::WrapCallbackWithDropHandler(std::move(callback),
        base::BindOnce(&Operation::Failed, shared_from_this(), "connection_lost_unknown"));
  }

  void Fetch() {
    auto* manager = Manager();
    if (!manager) { Failed("profile_unavailable"); return; }
    manager->GetAllCookies(Guard(base::BindOnce(&Operation::Fetched, shared_from_this())));
  }

  void SetNext() {
    if (finished_) return;
    auto* manager = Manager();
    if (!manager) { Failed("profile_unavailable"); return; }
    if (next_ == expected_.size()) {
      manager->FlushCookieStore(Guard(base::BindOnce(&Operation::Fetch, shared_from_this())));
      return;
    }
    const auto& cookie = expected_[next_++];
    // Use a secure source for Secure cookies even when legacy source metadata
    // is unset; metadata itself remains intact in CanonicalCookie.
    GURL source = SourceUrlFor(cookie);
    manager->SetCanonicalCookie(cookie, source, net::CookieOptions::MakeAllInclusive(),
        Guard(base::BindOnce(&Operation::SetDone, shared_from_this())));
  }

  void SetDone(net::CookieAccessResult result) {
    if (finished_) return;
    if (result.status.IsInclude()) ++accepted_;
    SetNext();
  }

  void Fetched(const net::CookieList& cookies) {
    if (finished_) return;
    base::DictValue result;
    if (importing_) {
      int verified = 0;
      for (const auto& expected : expected_) {
        for (const auto& actual : cookies) {
          if (actual.IsEquivalent(expected) && actual.Value() == expected.Value() &&
              actual.IsHttpOnly() == expected.IsHttpOnly() &&
              actual.SecureAttribute() == expected.SecureAttribute() &&
              actual.SameSite() == expected.SameSite() &&
              actual.IsPersistent() == expected.IsPersistent() &&
              (!actual.IsPersistent() || actual.ExpiryDate() == expected.ExpiryDate()) &&
              actual.Priority() == expected.Priority() &&
              actual.SourceScheme() == expected.SourceScheme() &&
              actual.SourcePort() == expected.SourcePort() &&
              actual.SourceType() == expected.SourceType()) { ++verified; break; }
        }
      }
      result.Set("requested", static_cast<int>(expected_.size()));
      result.Set("accepted", accepted_);
      result.Set("verified", verified);
      result.Set("ok", accepted_ == static_cast<int>(expected_.size()) && accepted_ == verified);
    } else {
      if (cookies.size() > kMaxCookies) { Failed("output_count"); return; }
      base::ListValue list;
      int skipped = 0;
      for (const auto& cookie : cookies) {
        auto value = Encode(cookie);
        if (value) list.Append(std::move(*value));
        else ++skipped;
      }
      result.Set("count", static_cast<int>(list.size()));
      result.Set("skipped", skipped);
      result.Set("cookies", std::move(list));
      result.Set("ok", true);
    }
    Complete(std::move(result));
  }

  void Failed(std::string error) {
    if (finished_) return;
    base::DictValue result;
    result.Set("ok", false);
    result.Set("error", std::move(error));
    result.Set("requested", static_cast<int>(expected_.size()));
    result.Set("accepted", accepted_);
    result.Set("attempted", static_cast<int>(next_));
    Complete(std::move(result));
  }

  void Complete(base::DictValue result) {
    if (finished_) return;
    finished_ = true;
    timer_.Stop();
    auto json = base::WriteJson(result);
    if (!json || json->size() > kMaxBytes) json = "{\"ok\":false,\"error\":\"output_size\"}";
    base::android::RunStringCallbackAndroid(callback_, *json);
    callback_.Reset();
    expected_.clear();
  }

  base::WeakPtr<Profile> profile_;
  base::android::ScopedJavaGlobalRef<jobject> callback_;
  base::OneShotTimer timer_;
  std::vector<net::CanonicalCookie> expected_;
  size_t next_ = 0;
  int accepted_ = 0;
  bool importing_ = false;
  bool finished_ = false;
};

Operation::Operation(Profile* profile, const base::android::JavaRef<jobject>& callback)
    : profile_(profile->GetWeakPtr()), callback_(callback) {}

Operation::~Operation() = default;

}  // namespace landu_cookie_transfer

static void JNI_CookiesFetcher_TransferForLandu(
    JNIEnv* env, Profile* profile, bool importing, const std::string& json,
    const base::android::JavaRef<jobject>& callback) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  if (!profile || profile->IsOffTheRecord() ||
      !base::CommandLine::ForCurrentProcess()->HasSwitch("landu-environment-id")) {
    base::android::RunStringCallbackAndroid(callback,
        "{\"ok\":false,\"error\":\"profile_unavailable\"}");
    return;
  }
  std::make_shared<landu_cookie_transfer::Operation>(profile, callback)->Start(importing, json);
}
