Build Monitor
=============

This project hooks up a Jenkins CI to a Karotz Build Bunny. Karotz Bunnies expose a webservice for control, but it is
very easy to overwhelm and so this project uses Akka to limit and prioritise messages to it and to schedule regular
Karotz API token token refresh calls.

It is currently built on Play 2.0 because that was quick to setup and may allow the project to be used as a
dashboard at a later date.

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
            "apiKey" : "<KAROTZ_API_KEY>",
            "secretKey" : "<KAROTZ_SECRET_KEY>",
            "installId" : "<KAROTZ_INSTALL_ID>",
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

* Turn into a Play Module
* Add more tests
* Figure out why Spray Can Client doesn't play nice (see: [Google Groups Post][sprayCanProblems].
* Refactor out dependency on [MRitchie's Karotz lib][mRitchieKarotzApi] and make properly async.
* Add common error paths (some particularly egregious handling of failed Actors right now)





[installingPlay]: http://www.playframework.org/documentation/2.0.2/Installing "Installing Play 2.0"
[sprayCanProblems]: https://groups.google.com/forum/?fromgroups=#!topic/spray-user/rxCvR7sjFOU "Spray Can Problems"
[mRitchieKarotzApi]: https://github.com/ritchiem/Karotz-Java-API "Martin Ritchie's Karotz Java API"