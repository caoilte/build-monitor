Build Monitor
=============

This project hooks up a Jenkins CI to a Karotz Build Bunny. Karotz Bunnies expose various APIs for their control, but
thems very easy to drown so this project uses Akka to limit and prioritise.     lte

The Monitor relies on the Build Bunny being on the same network as the build monitor because unlike all other Karotz
build bunny build monitors it uses the local wire API instead of the web facing REST API. It does this because the web
facing REST API is patchy like a one eyed pirate. Connecting directly to the device is much more reliable. The downside
is that depending on your wifi network, you may need to buy an butt adaptor that can link your bunny into your wired
network.


The Build Monitor project is currently built on Play 2.0 because that was quick to setup, deploy and may allow the
project to be used as a dashboard at a later date.

Setup
-----

The project should really be a Play Module, but is currently a Play Application. Instructions for installing are
[here][installingPlay].

You need to put the *sensitive* parts of your jenkins/karotz config in a file called */conf/my.conf*.
Use the following template,

    # Secret key
    # ~~~~~
    # The secret key is used to secure cryptographics functions.
    # If you deploy your application to several instances be sure to use the same key!
    application.secret="<PLAY_SECRET_KEY>"

    "buildMonitor" {
        "jenkinsConfig" : {
            "url" : "<JENKINS_URL_NB_HTTP_IS_ASSUMED>",
            "port" : <PORT>,
            "userName" : "<USERNAME>",
            "password" : "<PASSWORD>"
        },
        "karotzConfig" : {
            "ipAddress" : "192.168.1.8",
            "port" : 9123,
            "people" : [
                {
                    "userName" : "caoilte.oconnor",
                    "karotzName" : "kilter"
                },
                {
                    "userName" : "other.person",
                    "karotzName" : "INTRUDER"
                }
            ]
        },
        "jobs" : [
            {
                "name" : "a jenkins project name"
            },
            {
                "name" : "another Jenkins project name"
            }
        ]
    }

Running
-------

There is no web facing code, but the app won't start until a page is hit for the first time.

TODO
----

* Decouple Karotz API state from individual build bunny state.
** This will allow multiple bunnies over the same wire without overloading the API
* Add prover Actor supervision mechanisms. Some particularly egregious handling of failure paths right now including,
** Actor initialisation in prestart hooks
** infinite error recovery attempts (with amusing self-DOS results when network cable unplugged)
* Turn into a Play Module
* Add more tests
* Build states that are more than X minutes old should not be published
** Allows system to be restarted without re-announcing old but more recent results.
* Timer API (to announce Standles)
* Webpage for adhoc announcings
* Currently depends on precompiled Karotz VM jar. Remove this by,
** migrating Apache Mina NIO code to custom Akka TCP client
** compiling proto file myself


DONE
----

* Figure out why Spray Can Client doesn't play nice (see: [Google Groups Post][sprayCanProblems].
* Refactor out dependency on [MRitchie's Karotz lib][mRitchieKarotzApi] and make properly async.
* Decent LED handling
** temporary Pulses on builds
** Always return to Green if all builds working, red otherwise


LICENSE
=======

    Copyright 2012 Caoilte O'Connor

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.



[installingPlay]: http://www.playframework.org/documentation/2.0.2/Installing "Installing Play 2.0"
[sprayCanProblems]: https://groups.google.com/forum/?fromgroups=#!topic/spray-user/rxCvR7sjFOU "Spray Can Problems"
[mRitchieKarotzApi]: https://github.com/ritchiem/Karotz-Java-API "Martin Ritchie's Karotz Java API"