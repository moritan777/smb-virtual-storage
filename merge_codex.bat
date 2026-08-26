git fetch --all --prune
git checkout master
git pull

for /f %%i in ('git for-each-ref --sort=-committerdate --format="%%(refname:short)" refs/remotes/origin/codex/*') do (
    git merge -X theirs %%i
    goto :done
)

:done
git push origin master