import gulp from 'gulp';
import autoprefixer from 'autoprefixer';
import eslint from 'gulp-eslint';
import rimraf from 'rimraf';
import browserSync, { reload } from 'browser-sync';
import sourcemaps from 'gulp-sourcemaps';
import postcss from 'gulp-postcss';
import nested from 'postcss-nested';
import vars from 'postcss-simple-vars';
import extend from 'postcss-simple-extend';
import cssnano from 'cssnano';
import runSequence from 'run-sequence';
import ghPages from 'gulp-gh-pages';
import path from 'path';
import cp from 'child_process';
import webpack from 'webpack';
import webpackDevMiddleware from 'webpack-dev-middleware';
import webpackHotMiddleware from 'webpack-hot-middleware';
import env from 'gulp-env';
import config from './config';

const paths = {
  bundle: 'app.js',
  srcJsx: 'src/app.js',
  srcServer: 'src/server.js',
  srcCss: 'src/**/*.css',
  srcFonts: 'src/fonts/**',
  srcImg: 'src/images/**',
  srcPublic: 'src/public/**',
  srcLint: ['src/**/*.js', 'test/**/*.js'],
  dist: 'dist/public',
  distDeploy: './dist/public/**/*'
};

gulp.task('clean', cb => {
  rimraf('dist', cb);
});

gulp.task('browserSync', ['serve'], () => {
  const bundler = webpack(config[0]);
  browserSync({
    proxy: {
      target: 'localhost:5000',
      middleware: [
        webpackDevMiddleware(bundler, {
          publicPath: '/',
          stats: config[0].stats
        }),
        webpackHotMiddleware(bundler)
      ]
    },
    files: ['dist/public/**/*.css', 'dist/public/**/*.html']
  });
});

const webpackConfig = {};

gulp.task('serve', done => {
  console.log(`Stats : ${JSON.stringify(config.stats)}`);
  const bundler = webpack(config);
  const start = () => {
    const server = cp.fork('server.js', {
      cwd: path.join(__dirname, './dist'),
      env: Object.assign({ NODE_ENV: 'development' }, process.env),
      silent: false
    });
    server.once('message', message => {
      if (message.match(/^online$/)) {
        console.log('Server is online...');
        if (!webpackConfig.isGulpTaskDone) {
          done();
          webpackConfig.isGulpTaskDone = true;
        }
      }
    });
    server.once('error', err => console.log(`Server startup failed ${err}`));
    process.on('exit', () => server.kill('SIGTERM'));
    return server;
  };
  const bundle = (err, stats) => {
    if (err) {
      console.log(`Bundle errors! ${err}`);
    }

    console.log(stats.toString(config[0].stats));
    if (!webpackConfig.serverInstance) {
      webpackConfig.serverInstance = start();
    } else {
      webpackConfig.serverInstance.kill('SIGTERM');
      webpackConfig.serverInstance = start();
    }
  };
  bundler.watch(200, bundle);
});

gulp.task('server-bundle', done => {
  webpack(config, (err, stats) => {
    // eslint-disable-next-line no-undef
    if (err) throw new gutil.PluginError('webpack:build', err);
    console.log(
      `[webpack:build]${stats.toString({
        colors: true
      })}`
    );
    done();
  });
});

gulp.task('styles', () => {
  gulp
    .src(paths.srcCss)
    .pipe(sourcemaps.init())
    .pipe(postcss([vars, extend, nested, autoprefixer, cssnano]))
    .pipe(sourcemaps.write('.'))
    .pipe(gulp.dest(paths.dist))
    .pipe(reload({ stream: true }));
});

gulp.task('public', () => {
  gulp.src(paths.srcPublic).pipe(gulp.dest(paths.dist));
});

gulp.task('fonts', () => {
  gulp.src(paths.srcFonts).pipe(gulp.dest(`${paths.dist}/fonts`));
});

gulp.task('images', () => {
  gulp.src(paths.srcImg)
    .pipe(gulp.dest(paths.dist + '/images'));
});

// There are too many linting error. this needs to be disabled
// for now. It is causing watch to fail.
// After we fix all of the linting errors, we can enable it.
gulp.task('lint', () => {
  gulp
    .src(paths.srcLint)
    .pipe(eslint())
    .pipe(eslint.format());
});

gulp.task('watchTask', () => {
  gulp.watch(paths.srcFonts, ['fonts']);
  gulp.watch(paths.srcCss, ['styles']);
  gulp.watch(paths.srcPublic, ['public']);
  paths.srcLint.forEach(src => {
    gulp.watch(src, ['lint']);
  });
});

gulp.task('deploy', () => gulp.src(paths.distDeploy).pipe(ghPages()));

gulp.task('watch', cb => {
  runSequence('clean', ['set-env', 'browserSync', 'watchTask', 'public', 'styles', 'fonts', 'images'], cb);
});

gulp.task('set-env', () => {
  // Only use localhost if WF_SERVER is not set
  const wfServer = process.env.WF_SERVER || 'http://localhost:8080/api/';
  env({
    vars: {
      WF_SERVER: wfServer
    }
  });
});

gulp.task('build', cb => {
  process.env.DEBUG = false;
  runSequence('clean', ['server-bundle', 'styles', 'fonts', 'public', 'images'], cb);
});
