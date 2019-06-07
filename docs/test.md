My Project
==========

This is a prototype Java project.

Image: ![Travis CI](https://travis-ci.org/rundeck/rundeck.svg?branch=master)

Linked image: [![Travis CI](https://travis-ci.org/rundeck/rundeck.svg?branch=master)](https://travis-ci.org/rundeck/rundeck/builds#)

TODO:

- [ ] update gradle.properties: set group name
- [ ] move `travis-template.yaml` to `.travis.yaml`, update contents
- [ ] to enable travis releases, run `travis setup releases` to setup deployment
- [ ] update readme
- [ ] add a "LICENSE" file if public
- [ ] commit to git!

1. avoid
2. evade
3. evoid

Another Project
---------------

* don't bother me
* i'm *excelling* at this
* otherwise I _differ_
* and [Always](http://google.com "pillow") **creative**
* code `inline` and `with "special" && escaped < characters > see`

> quote
> text is always
> fantastico

	I also like
	the way this looks
	in code, weee haw

And this is now fenced:

~~~ {.java}
package example;

import org.rundeck.toolbelt.*;

import java.io.IOException;

@SubCommand
public class App {

    public static void main(String[] args) throws IOException, CommandRunFailure {
        ToolBelt.with("example", new App()).runMain(args, true);
    }

    @Command(description = "Simple example")
    public void simple() {
        System.out.println("Easily create commandlines with simple annotation");
    }

    @Command(description = "Fancy example")
    public boolean fancy(@Arg("string") String val) {
        System.out.println("Basic commandline parsing " + val);
        return true;
    }
}
~~~
