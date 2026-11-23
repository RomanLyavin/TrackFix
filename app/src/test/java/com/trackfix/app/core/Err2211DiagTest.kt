package com.trackfix.app.core

import org.junit.Test
import java.io.File
import java.time.Duration

/** Temporary diagnostics for the 22.11.2026 bug report. */
class Err2211DiagTest {

    private fun resource(name: String): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
        check(stream != null) { "missing $name" }
        return stream!!.use { it.readBytes() }
    }

    private fun fmt(sec: Double): String {
        val m = (sec / 60).toInt()
        val s = (sec % 60).toInt()
        return "%02d:%02d".format(m, s)
    }

    @Test
    fun dump() {
        val out = StringBuilder()
        val fit = FitCodec.parse(resource("err2211_source.fit"))
        val points = fit.records.map { it.toTrackPoint() }
        val refFile = GpxCodec.parse(resource("err2211_reference.gpx"), reference = true)
        val reference = ReferencePath(GpxCodec.referencePaths(refFile))
        val path = reference.paths[0]
        out.append("source points=").append(points.size)
            .append(" first=").append(points.first().time).append(" last=").append(points.last().time).append("\n")
        out.append("ref length=").append(path.totalLength)
            .append(" closed=").append(path.closed)
            .append(" repeatLapCount=").append(path.repeatLapCount)
            .append(" repeatLapLength=").append(path.repeatLapLength).append("\n")
        val t0 = points.first().time!!
        val missing = points.count { !it.hasCoordinates }
        out.append("missing coords=").append(missing).append("\n")
        out.append("--- source jumps > 20 m ---\n")
        var jumps = 0
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            if (!a.hasCoordinates || !b.hasCoordinates) continue
            val d = Geo.distanceMeters(a.lat!!, a.lon!!, b.lat!!, b.lon!!)
            if (d > 20.0) {
                jumps++
                val sec = Duration.between(t0, b.time).toSeconds().toDouble()
                out.append("idx=").append(i).append(" ").append("%.1f".format(d))
                    .append(" m t=").append(fmt(sec))
                    .append(" dist=").append(b.cumulativeDistanceMeters).append("\n")
            }
        }
        out.append("jumps=").append(jumps).append("\n")
        out.append("--- source points > 25 m from reference ---\n")
        var devRuns = 0
        var inRun = false
        for (i in points.indices) {
            val p = points[i]
            if (!p.hasCoordinates) continue
            val proj = reference.nearest(p.lat!!, p.lon!!, 200.0)
            val dev = proj?.distance ?: -1.0
            if (dev > 25.0) {
                if (!inRun) {
                    devRuns++
                    inRun = true
                    val sec = Duration.between(t0, p.time).toSeconds().toDouble()
                    out.append("run start idx=").append(i).append(" dev=").append("%.1f".format(dev))
                        .append(" t=").append(fmt(sec))
                        .append(" dist=").append(p.cumulativeDistanceMeters)
                        .append(" speed=").append(p.speedMetersPerSecond).append("\n")
                }
            } else {
                if (inRun) {
                    val sec = Duration.between(t0, p.time).toSeconds().toDouble()
                    out.append("  run end idx=").append(i).append(" t=").append(fmt(sec)).append("\n")
                    inRun = false
                }
            }
        }
        out.append("devRuns>25m=").append(devRuns).append("\n")
        val fixer = TrackFixer(reference, FixSettings(30.0, fillMissingElevation = true))
        val res = fixer.fix(points)
        out.append("--- fixer stats (30 m) ---\n")
        out.append(res.stats.toString()).append("\n")
        out.append("warnings=").append(res.warnings).append("\n")
        out.append("--- output jumps > 20 m ---\n")
        var ojumps = 0
        for (i in 1 until points.size) {
            val aLat = res.lat[i - 1]; val aLon = res.lon[i - 1]
            val bLat = res.lat[i]; val bLon = res.lon[i]
            if (aLat == null || aLon == null || bLat == null || bLon == null) continue
            val d = Geo.distanceMeters(aLat!!, aLon!!, bLat!!, bLon!!)
            if (d > 20.0) {
                ojumps++
                val sec = Duration.between(t0, points[i].time!!).toSeconds().toDouble()
                out.append("idx=").append(i).append(" ").append("%.1f".format(d))
                    .append(" m t=").append(fmt(sec))
                    .append(" changed=").append(res.coordinateChanged[i]).append("\n")
            }
        }
        out.append("ojumps=").append(ojumps).append("\n")
        out.append("--- output worst 20 deviations from reference ---\n")
        val devs = ArrayList<Pair<Double, Int>>()
        for (i in points.indices) {
            val aLat = res.lat[i]; val aLon = res.lon[i]
            if (aLat == null || aLon == null) continue
            val proj = reference.nearest(aLat!!, aLon!!, 500.0)
            devs.add((proj?.distance ?: 999.0) to i)
        }
        devs.sortedByDescending { it.first }.take(20).forEach { (d, i) ->
            val sec = Duration.between(t0, points[i].time!!).toSeconds().toDouble()
            out.append("dev=").append("%.1f".format(d)).append(" idx=").append(i)
                .append(" t=").append(fmt(sec))
                .append(" changed=").append(res.coordinateChanged[i]).append("\n")
        }
        // 10 m threshold run (the user's attempt)
        val res10 = TrackFixer(reference, FixSettings(10.0, fillMissingElevation = true)).fix(points)
        out.append("--- stats at 10 m ---\n")
        out.append("fixed=").append(res10.stats.fixedCoordinates)
            .append(" blocks=").append(res10.stats.problemBlocks)
            .append(" unresolved=").append(res10.stats.unresolvedPoints)
            .append(" distAfter=").append("%.1f".format(res10.stats.distanceAfter)).append("\n")
        var o10 = 0
        var o10max = 0.0
        for (i in 1 until points.size) {
            val aLat = res10.lat[i - 1]; val aLon = res10.lon[i - 1]
            val bLat = res10.lat[i]; val bLon = res10.lon[i]
            if (aLat == null || aLon == null || bLat == null || bLon == null) continue
            val d = Geo.distanceMeters(aLat!!, aLon!!, bLat!!, bLon!!)
            if (d > o10max) o10max = d
            if (d > 20.0) o10++
        }
        out.append("jumps>20m=").append(o10).append(" maxStep=").append("%.1f".format(o10max)).append("\n")
        var d10max = 0.0
        for (i in points.indices) {
            val aLat = res10.lat[i]; val aLon = res10.lon[i]
            if (aLat == null || aLon == null) continue
            val proj = reference.nearest(aLat!!, aLon!!, 500.0)
            d10max = maxOf(d10max, proj?.distance ?: 0.0)
        }
        out.append("maxDevAfter(10m)=").append("%.1f".format(d10max)).append("\n")
        // clean RealDataTest source: max deviation from its reference
        val cleanFile = GpxCodec.parse(resource("orig_file.gpx"))
        val cleanRef = ReferencePath(GpxCodec.referencePaths(GpxCodec.parse(resource("correct_track.gpx"), reference = true)))
        val cleanPoints = cleanFile.trackPoints()
        var cmax = 0.0
        for (p in cleanPoints) {
            if (!p.hasCoordinates) continue
            val proj = cleanRef.nearest(p.lat!!, p.lon!!, 300.0)
            cmax = maxOf(cmax, proj?.distance ?: 0.0)
        }
        out.append("cleanSourceMaxDev=").append("%.1f".format(cmax)).append("\n")
        val cleanRes = TrackFixer(cleanRef, FixSettings(30.0)).fix(cleanPoints)
        out.append("cleanFixed=").append(cleanRes.stats.fixedCoordinates).append("\n")
        File("app/build/test-output").mkdirs()
        File("app/build/test-output/err2211-diagnostics.txt").writeText(out.toString())
    }
}
