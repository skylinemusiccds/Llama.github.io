package hex.kmeans;

import hex.Model;
import hex.ModelBuilder;
import hex.schemas.KMeansV2;
import hex.schemas.ModelBuilderSchema;
import water.*;
import water.H2O.H2OCountedCompleter;
import water.fvec.Chunk;
import water.fvec.Vec;
import water.util.ArrayUtils;
import water.util.Log;
import water.util.RandomUtils;

import java.util.ArrayList;
import java.util.Random;

/**
 * Scalable K-Means++ (KMeans||)<br>
 * http://theory.stanford.edu/~sergei/papers/vldb12-kmpar.pdf<br>
 * http://www.youtube.com/watch?v=cigXAxV3XcY
 */
public class KMeans extends ModelBuilder<KMeansModel,KMeansModel.KMeansParameters,KMeansModel.KMeansOutput> {
  @Override
  public Model.ModelCategory[] can_build() {
    return new Model.ModelCategory[]{
      Model.ModelCategory.Clustering,
    };
  }

  public enum Initialization {
    None, PlusPlus, Furthest
  }

  // Number of categorical columns
  private int _ncats;

  // Number of reinitialization attempts for preventing empty clusters
  transient private int _reinit_attempts;

  // Called from an http request
  public KMeans( KMeansModel.KMeansParameters parms ) { super("K-means",parms); init(false); }

  public ModelBuilderSchema schema() { return new KMeansV2(); }


  /** Start the KMeans training Job on an F/J thread. */
  @Override public Job<KMeansModel> trainModel() {
    return start(new KMeansDriver(), _parms._max_iters);
  }
  
  /** Initialize the ModelBuilder, validating all arguments and preparing the
   *  training frame.  This call is expected to be overridden in the subclasses
   *  and each subclass will start with "super.init();".
   *
   *  Validate K, max_iters and the number of rows.  Precompute the number of
   *  categorical columns. */
  @Override public void init(boolean expensive) {
    super.init(expensive);
    if( _parms._k < 1 || _parms._k > 10000000 ) error("_k", "k must be between 1 and 1e7");
    if( _parms._max_iters < 1 || _parms._max_iters > 1000000) error("_max_iters", " max_iters must be between 1 and 1e6");
    if( _train == null ) return; // Nothing more to check
    if( _train.numRows() < _parms._k ) error("_k","Cannot make " + _parms._k + " clusters out of " + _train.numRows() + " rows.");

    for( Vec v : _train.vecs() )
      if( v.isEnum() ) _ncats++;

    // Sort columns, so the categoricals are all up front.  They use a
    // different distance metric than numeric columns.
    Vec vecs[] = _train.vecs();
    int ncats=0, len=vecs.length; // Feature count;
    while( ncats != len ) {
      while( ncats < len && vecs[ncats].isEnum() ) ncats++;
      while( len > 0 && !vecs[len-1].isEnum() ) len--;
      if( ncats < len-1 ) _train.swap(ncats,len-1);
    }
    _ncats = ncats;
  }

  // ----------------------
  private class KMeansDriver extends H2OCountedCompleter<KMeansDriver> {

    @Override protected void compute2() {

      KMeansModel model = null;
      try {
        _parms.read_lock_frames(KMeans.this); // Fetch & read-lock input frames
        init(true);

        // The model to be built
        model = new KMeansModel(dest(), _parms, new KMeansModel.KMeansOutput(KMeans.this));
        model.delete_and_lock(_key);

        // means are used to impute NAs
        model._output._ncats = _ncats;
        Vec vecs[] = _train.vecs();
        final int N = vecs.length; // Feature count
        double[] means = new double[N];
        for( int i = 0; i < N; i++ )
          means[i] = vecs[i].mean();
        // mults & means for standardization
        double[] mults = null;
        if( _parms._standardize ) {
          mults = new double[N];
          for( int i = 0; i < N; i++ ) {
            double sigma = vecs[i].sigma();
            mults[i] = standardize(sigma) ? 1.0 / sigma : 1.0;
          }
        }

        // Initialize clusters
        Random rand = water.util.RandomUtils.getRNG(_parms._seed - 1);
        double clusters[][];    // Standardized cluster centers
        if( _parms._init == Initialization.None ) {
          // Initialize all clusters to random rows
          clusters = model._output._clusters = new double[_parms._k][_train.numCols()];
          for( double[] cluster : clusters )
            randomRow(vecs, rand, cluster, means, mults);
        } else {
          clusters = new double[1][vecs.length];
          // Initialize first cluster to random row
          randomRow(vecs, rand, clusters[0], means, mults);

          while( model._output._iters < 5 ) {
            // Sum squares distances to clusters
            SumSqr sqr = new SumSqr(clusters,means,mults,_ncats).doAll(vecs);

            // Sample with probability inverse to square distance
            Sampler sampler = new Sampler(clusters, means, mults, _ncats, sqr._sqr, _parms._k * 3, _parms._seed).doAll(vecs);
            clusters = ArrayUtils.append(clusters,sampler._sampled);

            // Fill in sample clusters into the model
            if( !isRunning() ) return; // Stopped/cancelled
            model._output._clusters = destandardize(clusters, _ncats, means, mults);
            model._output._avgwithinss = sqr._sqr/_train.numRows();

            model._output._iters++;     // One iteration done

            // This doesn't count towards model building (we didn't account these iterations as work to be done during construction)
            // update(1);          // One unit of work

            model.update(_key); // Early version of model is visible
          }
          // Recluster down to K standardized clusters
          clusters = recluster(clusters, rand);
        }
        model._output._iters = 0;     // Reset iteration count

        // ---
        // Run the main KMeans Clustering loop
        // Stop after enough iterations
        LOOP:
        for( ; model._output._iters < _parms._max_iters; model._output._iters++ ) {
          if( !isRunning() ) return; // Stopped/cancelled
          Lloyds task = new Lloyds(clusters,means,mults,_ncats, _parms._k).doAll(vecs);
          // Pick the max categorical level for clusters' center
          max_cats(task._cMeans,task._cats);

          // Handle the case where some clusters go dry.  Rescue only 1 cluster
          // per iteration ('cause we only tracked the 1 worst row)
          boolean badrow=false;
          for( int clu=0; clu<_parms._k; clu++ ) {
            if (task._rows[clu] == 0) {
              // If we see 2 or more bad rows, just re-run Lloyds to get the
              // next-worst row.  We don't count this as an iteration, because
              // we're not really adjusting the centers, we're trying to get
              // some centers *at-all*.
              if (badrow) {
                Log.warn("KMeans: Re-running Lloyds to re-init another cluster");
                model._output._iters--; // Do not count against iterations
                if (_reinit_attempts++ < _parms._k) {
                  continue LOOP;  // Rerun Lloyds, and assign points to centroids
                } else {
                  _reinit_attempts = 0;
                  break; //give up and accept empty cluster
                }
              }
              long row = task._worst_row;
              Log.warn("KMeans: Re-initializing cluster " + clu + " to row " + row);
              data(clusters[clu] = task._cMeans[clu], vecs, row, means, mults);
              task._rows[clu] = 1;
              badrow = true;
            }
          }

          // Fill in the model; destandardized centers
          model._output._names = _train.names();
          model._output._clusters = destandardize(task._cMeans, _ncats, means, mults);
          model._output._rows = task._rows;
          model._output._withinmse = task._cSqr;
          double ssq = 0;       // sum squared error
          for( int i=0; i<_parms._k; i++ ) {
            ssq += model._output._withinmse[i]; // sum squared error all clusters
            model._output._withinmse[i] /= task._rows[i]; // mse within-cluster
          }
          model._output._avgwithinss = ssq/_train.numRows(); // mse total

          // Sum-of-square distance from grand mean (since we auto-standardize data, this is just the origin)
          if(_parms._k == 1)
            model._output._avgss = model._output._avgwithinss;
          else {
            SumSqr totss = new SumSqr(new double[1][means.length],means,mults,_ncats).doAll(vecs);
            model._output._avgss = totss._sqr/_train.numRows(); // mse with respect to grand mean
          }
          model._output._avgbetweenss = model._output._avgss - model._output._avgwithinss;  // mse between-cluster
          model.update(_key); // Update model in K/V store
          update(1);          // One unit of work

          // Compute change in clusters centers
          double sum=0;
          for( int clu=0; clu<_parms._k; clu++ )
            sum += distance(clusters[clu],task._cMeans[clu],_ncats);
          sum /= N;             // Average change per feature
          Log.info("KMeans: Change in cluster centers="+sum);
          if( sum < 1e-6 ) break;  // Model appears to be stable
          clusters = task._cMeans; // Update cluster centers

          StringBuilder sb = new StringBuilder();
          sb.append("KMeans: iter: ").append(model._output._iters).append(", MSE=").append(model._output._avgwithinss);
          for( int i=0; i<_parms._k; i++ )
            sb.append(", ").append(task._cSqr[i]).append("/").append(task._rows[i]);
          Log.info(sb);
        }

      } catch( Throwable t ) {
        t.printStackTrace();
        cancel2(t);
        throw t;
      } finally {
        if( model != null ) model.unlock(_key);
        _parms.read_unlock_frames(KMeans.this);
        done();                 // Job done!
      }
      tryComplete();
    }
  }

  // -------------------------------------------------------------------------
  // Initial sum-of-square-distance to nearest cluster
  private static class SumSqr extends MRTask<SumSqr> {
    // IN
    double[][] _clusters;
    double[] _means, _mults; // Standardization
    final int _ncats;

    // OUT
    double _sqr;

    SumSqr( double[][] clusters, double[] means, double[] mults, int ncats ) {
      _clusters = clusters;
      _means = means;
      _mults = mults;
      _ncats = ncats;
    }

    @Override public void map(Chunk[] cs) {
      double[] values = new double[cs.length];
      ClusterDist cd = new ClusterDist();
      for( int row = 0; row < cs[0]._len; row++ ) {
        data(values, cs, row, _means, _mults);
        _sqr += minSqr(_clusters, values, _ncats, cd);
      }
      _means = _mults = null;
      _clusters = null;
    }

    @Override public void reduce(SumSqr other) { _sqr += other._sqr; }
  }

  // -------------------------------------------------------------------------
  // Sample rows with increasing probability the farther they are from any
  // cluster.
  private static class Sampler extends MRTask<Sampler> {
    // IN
    double[][] _clusters;
    double[] _means, _mults; // Standardization
    final int _ncats;
    final double _sqr;           // Min-square-error
    final double _probability;   // Odds to select this point
    final long _seed;

    // OUT
    double[][] _sampled;   // New clusters

    Sampler( double[][] clusters, double[] means, double[] mults, int ncats, double sqr, double prob, long seed ) {
      _clusters = clusters;
      _means = means;
      _mults = mults;
      _ncats = ncats;
      _sqr = sqr;
      _probability = prob;
      _seed = seed;
    }

    @Override public void map(Chunk[] cs) {
      double[] values = new double[cs.length];
      ArrayList<double[]> list = new ArrayList<>();
      Random rand = RandomUtils.getRNG(_seed + cs[0].start());
      ClusterDist cd = new ClusterDist();

      for( int row = 0; row < cs[0]._len; row++ ) {
        data(values, cs, row, _means, _mults);
        double sqr = minSqr(_clusters, values, _ncats, cd);
        if( _probability * sqr > rand.nextDouble() * _sqr )
          list.add(values.clone());
      }

      _sampled = new double[list.size()][];
      list.toArray(_sampled);
      _clusters = null;
      _means = _mults = null;
    }

    @Override public void reduce(Sampler other) {
      _sampled = ArrayUtils.append(_sampled, other._sampled);
    }
  }

  // ---------------------------------------
  // A Lloyd's pass:
  //   Find nearest cluster for every point;
  //   Compute new mean/center & variance & rows for each cluster;
  //   Compute distance between clusters
  //   Compute total sqr distance

  private static class Lloyds extends MRTask<Lloyds> {
    // IN
    double[][] _clusters;
    double[] _means, _mults;      // Standardization
    final int _ncats, _k;

    // OUT
    double[][] _cMeans;         // Means for each cluster
    long[/*k*/][/*ncats*/][] _cats; // Histogram of cat levels
    double[] _cSqr;             // Sum of squares for each cluster
    long[] _rows;               // Rows per cluster
    long _worst_row;            // Row with max err
    double _worst_err;          // Max-err-row's max-err

    Lloyds( double[][] clusters, double[] means, double[] mults, int ncats, int k ) {
      _clusters = clusters;
      _means = means;
      _mults = mults;
      _ncats = ncats;
      _k = k;
    }

    @Override public void map(Chunk[] cs) {
      int N = cs.length;
      assert _clusters[0].length==N;
      _cMeans = new double[_k][N];
      _cSqr = new double[_k];
      _rows = new long[_k];
      // Space for cat histograms
      _cats = new long[_k][_ncats][];
      for( int clu=0; clu< _k; clu++ )
        for( int col=0; col<_ncats; col++ )
          _cats[clu][col] = new long[cs[col].vec().cardinality()];
      _worst_err = 0;

      // Find closest cluster for each row
      double[] values = new double[N];
      ClusterDist cd = new ClusterDist();
      for( int row = 0; row < cs[0]._len; row++ ) {
        data(values, cs, row, _means, _mults);
        closest(_clusters, values, _ncats, cd);
        int clu = cd._cluster;
        assert clu != -1; // No broken rows
        _cSqr[clu] += cd._dist;

        // Add values and increment counter for chosen cluster
        for( int col = 0; col < _ncats; col++ )
          _cats[clu][col][(int)values[col]]++; // Histogram the cats
        for( int col = _ncats; col < N; col++ )
          _cMeans[clu][col] += values[col];
        _rows[clu]++;
        // Track worst row
        if( cd._dist > _worst_err) { _worst_err = cd._dist; _worst_row = cs[0].start()+row; }
      }
      // Scale back down to local mean
      for( int clu = 0; clu < _k; clu++ )
        if( _rows[clu] != 0 ) ArrayUtils.div(_cMeans[clu],_rows[clu]);
      _clusters = null;
      _means = _mults = null;
    }

    @Override public void reduce(Lloyds mr) {
      for( int clu = 0; clu < _k; clu++ ) {
        long ra =    _rows[clu];
        long rb = mr._rows[clu];
        double[] ma =    _cMeans[clu];
        double[] mb = mr._cMeans[clu];
        for( int c = 0; c < ma.length; c++ ) // Recursive mean
          if( ra+rb > 0 ) ma[c] = (ma[c] * ra + mb[c] * rb) / (ra + rb);
      }
      ArrayUtils.add(_cats, mr._cats);
      ArrayUtils.add(_cSqr, mr._cSqr);
      ArrayUtils.add(_rows, mr._rows);
      // track global worst-row
      if( _worst_err < mr._worst_err) { _worst_err = mr._worst_err; _worst_row = mr._worst_row; }
    }
  }

  // A pair result: nearest cluster, and the square distance
  private static final class ClusterDist { int _cluster; double _dist;  }

  private static double minSqr(double[][] clusters, double[] point, int ncats, ClusterDist cd) {
    return closest(clusters, point, ncats, cd, clusters.length)._dist;
  }

  private static double minSqr(double[][] clusters, double[] point, int ncats, ClusterDist cd, int count) {
    return closest(clusters,point,ncats,cd,count)._dist;
  }

  private static ClusterDist closest(double[][] clusters, double[] point, int ncats, ClusterDist cd) {
    return closest(clusters, point, ncats, cd, clusters.length);
  }

  private static double distance(double[] cluster, double[] point, int ncats) {
    double sqr = 0;             // Sum of dimensional distances
    int pts = point.length;     // Count of valid points

    // Categorical columns first.  Only equals/unequals matters (i.e., distance is either 0 or 1).
    for(int column = 0; column < ncats; column++) {
        double d = point[column];
      if( Double.isNaN(d) ) pts--;
      else if( d != cluster[column] )
        sqr += 1.0;           // Manhattan distance
    }
    // Numeric column distance
    for( int column = ncats; column < cluster.length; column++ ) {
      double d = point[column];
      if( Double.isNaN(d) ) pts--; // Do not count
      else {
        double delta = d - cluster[column];
        sqr += delta * delta;
      }
    }
    // Scale distance by ratio of valid dimensions to all dimensions - since
    // we did not add any error term for the missing point, the sum of errors
    // is small - ratio up "as if" the missing error term is equal to the
    // average of other error terms.  Same math another way:
    //   double avg_dist = sqr / pts; // average distance per feature/column/dimension
    //   sqr = sqr * point.length;    // Total dist is average*#dimensions
    if( 0 < pts && pts < point.length )
      sqr *= point.length / pts;
    return sqr;
  }

  /** Return both nearest of N cluster/centroids, and the square-distance. */
  private static ClusterDist closest(double[][] clusters, double[] point, int ncats, ClusterDist cd, int count) {
    int min = -1;
    double minSqr = Double.MAX_VALUE;
    for( int cluster = 0; cluster < count; cluster++ ) {
      double sqr = distance(clusters[cluster],point,ncats);
      if( sqr < minSqr ) {      // Record nearest cluster
        min = cluster;
        minSqr = sqr;
      }
    }
    cd._cluster = min;          // Record nearest cluster
    cd._dist = minSqr;          // Record square-distance
    return cd;                  // Return for flow-coding
  }

  // For KMeansModel scoring; just the closest cluster
  static int closest(double[][] clusters, double[] point, int ncats) {
    int min = -1;
    double minSqr = Double.MAX_VALUE;
    for( int cluster = 0; cluster < clusters.length; cluster++ ) {
      double sqr = distance(clusters[cluster],point,ncats);
      if( sqr < minSqr ) {      // Record nearest cluster
        min = cluster;
        minSqr = sqr;
      }
    }
    return min;
  }

  // KMeans++ re-clustering
  private double[][] recluster(double[][] points, Random rand) {
    double[][] res = new double[_parms._k][];
    res[0] = points[0];
    int count = 1;
    ClusterDist cd = new ClusterDist();
    switch( _parms._init ) {
    case None:
      break;
    case PlusPlus: { // k-means++
      while( count < res.length ) {
        double sum = 0;
        for (double[] point1 : points) sum += minSqr(res, point1, _ncats, cd, count);

        for (double[] point : points) {
          if (minSqr(res, point, _ncats, cd, count) >= rand.nextDouble() * sum) {
            res[count++] = point;
            break;
          }
        }
      }
      break;
    }
    case Furthest: { // Takes cluster further from any already chosen ones
      while( count < res.length ) {
        double max = 0;
        int index = 0;
        for( int i = 0; i < points.length; i++ ) {
          double sqr = minSqr(res, points[i], _ncats, cd, count);
          if( sqr > max ) {
            max = sqr;
            index = i;
          }
        }
        res[count++] = points[index];
      }
      break;
    }
    default:  throw H2O.fail();
    }
    return res;
  }

  private void randomRow(Vec[] vecs, Random rand, double[] cluster, double[] means, double[] mults) {
    long row = Math.max(0, (long) (rand.nextDouble() * vecs[0].length()) - 1);
    data(cluster, vecs, row, means, mults);
  }

  private static boolean standardize(double sigma) {
    // TODO unify handling of constant columns
    return sigma > 1e-6;
  }

  // Pick most common cat level for each cluster_centers' cat columns
  private static double[][] max_cats(double[][] clusters, long[][][] cats) {
    int K = cats.length;
    int ncats = cats[0].length;
    for( int clu = 0; clu < K; clu++ )
      for( int col = 0; col < ncats; col++ ) // Cats use max level for cluster center
        clusters[clu][col] = ArrayUtils.maxIndex(cats[clu][col]);
    return clusters;
  }

  private static double[][] destandardize(double[][] clusters, int ncats, double[] means, double[] mults) {
    int K = clusters.length;
    int N = clusters[0].length;
    double[][] value = new double[K][N];
    for( int clu = 0; clu < K; clu++ ) {
      System.arraycopy(clusters[clu],0,value[clu],0,N);
      if( mults!=null )         // Reverse standardization
        for( int col = ncats; col < N; col++ )
          value[clu][col] = value[clu][col] / mults[col] + means[col];
    }
    return value;
  }

  private static void data(double[] values, Vec[] vecs, long row, double[] means, double[] mults) {
    for( int i = 0; i < values.length; i++ ) {
      double d = vecs[i].at(row);
      values[i] = data(d, i, means, mults, vecs[i].cardinality());
    }
  }

  private static void data(double[] values, Chunk[] chks, int row, double[] means, double[] mults) {
    for( int i = 0; i < values.length; i++ ) {
      double d = chks[i].at0(row);
      values[i] = data(d, i, means, mults, chks[i].vec().cardinality());
    }
  }

  /**
   * Takes mean if NaN, standardize if requested.
   */
  private static double data(double d, int i, double[] means, double[] mults, int cardinality) {
    if(cardinality == -1) {
      if( Double.isNaN(d) )
        d = means[i];
      if( mults != null ) {
        d -= means[i];
        d *= mults[i];
      }
    } else {
      // TODO: If NaN, then replace with majority class?
      if(Double.isNaN(d))
        d = Math.min(Math.round(means[i]), cardinality-1);
    }
    return d;
  }
}
