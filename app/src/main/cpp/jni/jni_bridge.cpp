// JNI surface for com.unigis.indoormap.core.NativeCore. Data crosses as flat primitive
// arrays; the packed layouts are documented next to the Kotlin decoders in NativeCore.kt.

#include <jni.h>

#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "unigis/camera.hpp"
#include "unigis/campus.hpp"
#include "unigis/router.hpp"
#include "unigis/search.hpp"

using namespace unigis;

namespace {

/** Campus plus the walking graph built over it. Heap-allocated so the router's reference stays valid. */
struct Session {
    explicit Session(Campus c) : campus(std::move(c)), router(campus) {}
    Campus campus;
    Router router;
};

Session* session(jlong handle) { return reinterpret_cast<Session*>(handle); }

const Floor* floorOf(jlong handle, jint b, jint f) {
    const Campus& c = session(handle)->campus;
    if (b < 0 || b >= static_cast<jint>(c.buildings.size())) return nullptr;
    const auto& floors = c.buildings[b].floors;
    if (f < 0 || f >= static_cast<jint>(floors.size())) return nullptr;
    return &floors[f];
}

jfloatArray toJava(JNIEnv* env, const std::vector<float>& v) {
    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(v.size()));
    if (arr) env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(v.size()), v.data());
    return arr;
}

jintArray toJava(JNIEnv* env, const std::vector<jint>& v) {
    jintArray arr = env->NewIntArray(static_cast<jsize>(v.size()));
    if (arr) env->SetIntArrayRegion(arr, 0, static_cast<jsize>(v.size()), v.data());
    return arr;
}

jobjectArray toJava(JNIEnv* env, const std::vector<std::string>& v) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(v.size()), stringClass, nullptr);
    for (size_t i = 0; arr && i < v.size(); ++i) {
        // Data is UTF-8 without embedded NULs or 4-byte sequences (Cyrillic, ASCII), which
        // NewStringUTF's modified UTF-8 accepts as is.
        jstring s = env->NewStringUTF(v[i].c_str());
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    env->DeleteLocalRef(stringClass);
    return arr;
}

std::string fromJava(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

/** Pins a Kotlin-owned camera state array for the duration of one call. */
class CameraState {
public:
    CameraState(JNIEnv* env, jfloatArray arr) : env_(env), arr_(arr) {
        if (env->GetArrayLength(arr) >= cam::Count) data_ = env->GetFloatArrayElements(arr, nullptr);
    }
    ~CameraState() {
        if (data_) env_->ReleaseFloatArrayElements(arr_, data_, 0);
    }
    explicit operator bool() const { return data_ != nullptr; }
    float* get() const { return data_; }

private:
    JNIEnv* env_;
    jfloatArray arr_;
    float* data_ = nullptr;
};

void writePoint(JNIEnv* env, jfloatArray out, Vec2 p) {
    const float xy[2] = {p.x, p.y};
    env->SetFloatArrayRegion(out, 0, 2, xy);
}

}  // namespace

#define UNIGIS_FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_unigis_indoormap_core_NativeCore_##name

// ---- Campus ----------------------------------------------------------------

UNIGIS_FN(jlong, loadCampus)(JNIEnv* env, jobject, jstring json) {
    try {
        return reinterpret_cast<jlong>(new Session(loadCampus(fromJava(env, json))));
    } catch (const std::exception& e) {
        jclass ex = env->FindClass("java/lang/IllegalArgumentException");
        env->ThrowNew(ex, e.what());
        return 0;
    }
}

UNIGIS_FN(void, releaseCampus)(JNIEnv*, jobject, jlong handle) { delete session(handle); }

UNIGIS_FN(jobjectArray, campusInfo)(JNIEnv* env, jobject, jlong handle) {
    const Campus& c = session(handle)->campus;
    return toJava(env, std::vector<std::string>{c.name, c.attribution});
}

UNIGIS_FN(jint, buildingCount)(JNIEnv*, jobject, jlong handle) {
    return static_cast<jint>(session(handle)->campus.buildings.size());
}

UNIGIS_FN(jobjectArray, buildingStrings)(JNIEnv* env, jobject, jlong handle, jint b) {
    const Building& bld = session(handle)->campus.buildings.at(b);
    return toJava(env, std::vector<std::string>{bld.id, bld.name, bld.shortName});
}

/** [levels, placeholder(0/1), floor level 0, floor level 1, ...] */
UNIGIS_FN(jintArray, buildingLevels)(JNIEnv* env, jobject, jlong handle, jint b) {
    const Building& bld = session(handle)->campus.buildings.at(b);
    std::vector<jint> out{bld.levels, bld.placeholder ? 1 : 0};
    for (const Floor& f : bld.floors) out.push_back(f.level);
    return toJava(env, out);
}

/** Outline in campus meters: [x0, y0, x1, y1, ...] */
UNIGIS_FN(jfloatArray, buildingOutline)(JNIEnv* env, jobject, jlong handle, jint b) {
    const Building& bld = session(handle)->campus.buildings.at(b);
    std::vector<float> out;
    for (const Vec2& p : bld.outline.points) { out.push_back(p.x); out.push_back(p.y); }
    return toJava(env, out);
}

/** [floor name, room0 id, room0 name, room1 id, room1 name, ...] */
UNIGIS_FN(jobjectArray, floorStrings)(JNIEnv* env, jobject, jlong handle, jint b, jint f) {
    const Floor* floor = floorOf(handle, b, f);
    if (!floor) return nullptr;
    std::vector<std::string> out{floor->name};
    for (const Room& r : floor->rooms) { out.push_back(r.id); out.push_back(r.name); }
    return toJava(env, out);
}

/**
 * [bounds l, t, r, b, youAreHere x, y,
 *  nRooms, (category, l, t, r, b, area, anchor x, anchor y) * nRooms,
 *  nDoors, (x1, y1, x2, y2, exterior) * nDoors,
 *  nConnectors, (kind, x, y) * nConnectors]
 */
UNIGIS_FN(jfloatArray, floorGeometry)(JNIEnv* env, jobject, jlong handle, jint b, jint f) {
    const Floor* floor = floorOf(handle, b, f);
    if (!floor) return nullptr;
    std::vector<float> out{floor->bounds.left, floor->bounds.top, floor->bounds.right, floor->bounds.bottom,
                           floor->youAreHere.x, floor->youAreHere.y};
    out.push_back(static_cast<float>(floor->rooms.size()));
    for (const Room& r : floor->rooms) {
        out.insert(out.end(), {static_cast<float>(r.category), r.bounds.left, r.bounds.top, r.bounds.right,
                               r.bounds.bottom, r.area, r.anchor.x, r.anchor.y});
    }
    out.push_back(static_cast<float>(floor->doors.size()));
    for (const Door& d : floor->doors) out.insert(out.end(), {d.a.x, d.a.y, d.b.x, d.b.y, d.exterior ? 1.f : 0.f});
    out.push_back(static_cast<float>(floor->connectors.size()));
    for (const Connector& c : floor->connectors) out.insert(out.end(), {static_cast<float>(c.kind), c.pos.x, c.pos.y});
    return toJava(env, out);
}

UNIGIS_FN(jint, roomAt)(JNIEnv*, jobject, jlong handle, jint b, jint f, jfloat x, jfloat y) {
    const Floor* floor = floorOf(handle, b, f);
    return floor ? floor->roomAt({x, y}) : -1;
}

/** Hits as triples [building, floor, room, ...], best first. Negative b/f search everywhere. */
UNIGIS_FN(jintArray, searchRooms)(JNIEnv* env, jobject, jlong handle, jstring query, jint b, jint f) {
    std::vector<jint> out;
    for (const SearchHit& h : unigis::searchRooms(session(handle)->campus, fromJava(env, query), b, f)) {
        out.insert(out.end(), {h.building, h.floor, h.room});
    }
    return toJava(env, out);
}

/** [found(0/1), meters, seconds, nLegs, (building, floor, nPoints, x, y, ...) * nLegs] */
UNIGIS_FN(jfloatArray, route)(JNIEnv* env, jobject, jlong handle, jint fb, jint ff, jint fr, jint tb, jint tf, jint tr) {
    const Route r = session(handle)->router.route({fb, ff, fr}, {tb, tf, tr});
    std::vector<float> out{r.found ? 1.f : 0.f, r.length, r.seconds, static_cast<float>(r.legs.size())};
    for (const RouteLeg& leg : r.legs) {
        out.insert(out.end(), {static_cast<float>(leg.building), static_cast<float>(leg.floor),
                               static_cast<float>(leg.points.size())});
        for (const Vec2& p : leg.points) { out.push_back(p.x); out.push_back(p.y); }
    }
    return toJava(env, out);
}

// ---- Camera ------------------------------------------------------------------

UNIGIS_FN(jint, cameraStateSize)(JNIEnv*, jobject) { return cam::Count; }

UNIGIS_FN(void, cameraInit)(JNIEnv* env, jobject, jfloatArray state, jfloat l, jfloat t, jfloat r, jfloat b) {
    CameraState s(env, state);
    if (s) Camera::init(s.get(), {l, t, r, b});
}

UNIGIS_FN(void, cameraViewport)(JNIEnv* env, jobject, jfloatArray state, jfloat w, jfloat h) {
    CameraState s(env, state);
    if (s) Camera(s.get()).onViewportChanged(w, h);
}

UNIGIS_FN(void, cameraGesture)(JNIEnv* env, jobject, jfloatArray state, jfloat cx, jfloat cy, jfloat px, jfloat py,
                               jfloat zoom, jfloat rotation) {
    CameraState s(env, state);
    if (s) Camera(s.get()).onGesture({cx, cy}, {px, py}, zoom, rotation);
}

UNIGIS_FN(void, cameraFit)(JNIEnv* env, jobject, jfloatArray state) {
    CameraState s(env, state);
    if (s) Camera(s.get()).fitToScreen();
}

UNIGIS_FN(void, cameraPrepareFit)(JNIEnv* env, jobject, jfloatArray state) {
    CameraState s(env, state);
    if (s) Camera(s.get()).prepareFit();
}

UNIGIS_FN(void, cameraPrepareResetRotation)(JNIEnv* env, jobject, jfloatArray state) {
    CameraState s(env, state);
    if (s) Camera(s.get()).prepareResetRotation();
}

/** A NaN pivot zooms around the viewport center. */
UNIGIS_FN(void, cameraPrepareZoom)(JNIEnv* env, jobject, jfloatArray state, jfloat factor, jfloat px, jfloat py) {
    CameraState s(env, state);
    if (!s) return;
    Camera c(s.get());
    if (px != px || py != py) c.prepareZoomAtCenter(factor);
    else c.prepareZoom(factor, {px, py});
}

UNIGIS_FN(void, cameraPrepareFocus)(JNIEnv* env, jobject, jfloatArray state, jfloat l, jfloat t, jfloat r, jfloat b) {
    CameraState s(env, state);
    if (s) Camera(s.get()).prepareFocus({l, t, r, b});
}

UNIGIS_FN(void, cameraStep)(JNIEnv* env, jobject, jfloatArray state, jfloat t) {
    CameraState s(env, state);
    if (s) Camera(s.get()).step(t);
}

UNIGIS_FN(void, cameraToScreen)(JNIEnv* env, jobject, jfloatArray state, jfloat x, jfloat y, jfloatArray out) {
    Vec2 p;
    {
        CameraState s(env, state);
        if (!s) return;
        p = Camera(s.get()).toScreen({x, y});
    }
    writePoint(env, out, p);
}

UNIGIS_FN(void, cameraToWorld)(JNIEnv* env, jobject, jfloatArray state, jfloat x, jfloat y, jfloatArray out) {
    Vec2 p;
    {
        CameraState s(env, state);
        if (!s) return;
        p = Camera(s.get()).toWorld({x, y});
    }
    writePoint(env, out, p);
}
